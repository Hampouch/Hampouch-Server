package Hampouch.server.domain.user;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.ExpenseService;
import Hampouch.server.domain.user.dto.request.NicknameUpdateRequest;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserService;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/users/me/nickname의 동시성 경쟁 상태를 검증한다.
 *
 * AuthConcurrencyTest(회원가입 닉네임 중복)와 동일한 기법을 쓴다: raw JDBC로 별도 커넥션을 열어
 * 목표 닉네임을 가진 행을 커밋하지 않은 채로 붙잡아두고, 그 사이 실제 PATCH 요청이 사전 검사
 * (existsByNickname)는 통과하되 실제 UPDATE 시점에 MySQL 유니크 인덱스 잠금에 걸려 블로킹되는지,
 * holder가 커밋된 뒤 UserService.updateNickname의 saveAndFlush + DataIntegrityViolationException
 * 처리가 이를 409로 정확히 변환하는지 검증한다.
 */
@MySqlContainerTest
@AutoConfigureMockMvc
class UserConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    @Autowired
    ExpenseService expenseService;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    EmailSender emailSender;

    private record HttpResult(int status, String body) {
    }

    @Test
    @DisplayName("동시에 다른 회원이 먼저 같은 닉네임을 선점하면 변경이 거부되고 기존 닉네임이 유지된다")
    void updateNickname_rejectsWhenAnotherUserConcurrentlyClaimsSameNickname() throws Exception {
        String email = "concurrent-nickname-update-" + System.currentTimeMillis() + "@example.com";
        String holderEmail = "concurrent-nickname-holder-" + System.currentTimeMillis() + "@example.com";
        String originalNickname = "원래닉" + (System.currentTimeMillis() % 100000);
        String targetNickname = "탐나는닉" + (System.currentTimeMillis() % 100000);

        prepareVerifiedEmail(email);
        signup(email, originalNickname);
        String accessToken = login(email);

        try (Connection holderConn = jdbc.getDataSource().getConnection()) {
            holderConn.setAutoCommit(false);

            // raw JDBC로 목표 닉네임을 가진 유저를 커밋하지 않은 채로 직접 삽입해서 붙잡아둔다.
            // 이 상태에서는 existsByNickname(스냅샷 격리상 안 보임)은 통과하고, 실제 UPDATE 실행 시점에
            // MySQL의 유니크 인덱스 잠금에 걸려야 한다 - AuthConcurrencyTest의 회원가입 INSERT 경쟁과 동일한 원리.
            try (PreparedStatement ps = holderConn.prepareStatement(
                    "INSERT INTO users (email, nickname, provider, role, status, created_at, updated_at) " +
                            "VALUES (?, ?, 'LOCAL', 'USER', 'ACTIVE', NOW(), NOW())")) {
                ps.setString(1, holderEmail);
                ps.setString(2, targetNickname);
                ps.executeUpdate();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResult> call = executor.submit(() -> callUpdateNickname(accessToken, targetNickname));

                Thread.sleep(500);
                assertThat(call.isDone())
                        .as("실제 요청은 우리가 커밋하지 않은 동일 닉네임 INSERT와 충돌해 막혀 있어야 한다")
                        .isFalse();

                holderConn.commit();

                HttpResult result = call.get(10, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(409);
                assertThat(result.body()).contains("USER_NICKNAME_ALREADY_EXISTS");
            } finally {
                executor.shutdownNow();
            }
        }

        Integer targetNicknameCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE nickname = ?", Integer.class, targetNickname);
        assertThat(targetNicknameCount).as("목표 닉네임은 먼저 선점한 holder 한 명만 가지고 있어야 한다").isEqualTo(1);

        Integer originalNicknameCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ? AND nickname = ?", Integer.class, email, originalNickname);
        assertThat(originalNicknameCount).as("변경이 거부됐으므로 원래 유저의 닉네임은 그대로여야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("닉네임 변경과 지출 생성이 동시에 들어와도 UserOperationLock으로 직렬화되어 닉네임과 lastUpdated가 모두 보존된다")
    void updateNicknameAndCreateExpense_bothSurviveWhenRunConcurrently() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        User user = userRepository.save(User.createLocalUser(
                "user-lock-race-" + System.currentTimeMillis() + "@hampouch.test", "encoded", "락경쟁전"));
        Long userId = user.getId();

        // 두 서비스 모두 UserOperationLock으로 같은 유저 row에 SELECT ... FOR UPDATE를 걸므로,
        // 실제 동시 실행이어도 MySQL 행 락으로 직렬화된다 - 나중에 락을 얻는 쪽은 먼저 커밋된
        // 최신 상태를 다시 읽으므로, 어느 쪽이 먼저 끝나든 두 변경 모두 유실 없이 남아야 한다.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> nicknameFuture = executor.submit(() ->
                    userService.updateNickname(userId, new NicknameUpdateRequest("락경쟁후")));
            Future<?> expenseFuture = executor.submit(() ->
                    expenseService.create(userId, new ExpenseCreateRequest(
                            "점심", 8000, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null, today, null, null)));

            nicknameFuture.get(10, TimeUnit.SECONDS);
            expenseFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getNickname())
                .as("동시에 지출이 생성돼도 닉네임 변경 결과가 사라지면 안 된다").isEqualTo("락경쟁후");
        assertThat(reloaded.getLastUpdated())
                .as("동시에 닉네임이 바뀌어도 지출 생성으로 갱신된 lastUpdated가 사라지면 안 된다").isEqualTo(today);
    }

    private HttpResult callUpdateNickname(String accessToken, String nickname) {
        try {
            MvcResult result = mvc.perform(patch("/api/users/me/nickname")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"" + nickname + "\"}"))
                    .andReturn();
            return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void signup(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private void prepareVerifiedEmail(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }
}
