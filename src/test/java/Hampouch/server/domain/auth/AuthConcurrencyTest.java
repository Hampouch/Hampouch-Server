package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 동시 요청 상황에서의 경쟁 상태(race condition)를 검증한다.
 * 이 테스트들은 @Transactional을 클래스에 걸지 않는다 - 각 스레드가 별도의 실제
 * 트랜잭션/커넥션으로 요청을 보내야 경쟁 상태가 실제로 재현되기 때문이다
 * (클래스 레벨 @Transactional로 감싸면 모든 요청이 하나의 트랜잭션을 공유하게 되어
 *  의미가 없어진다). 대신 각 테스트가 만든 데이터는 테스트 끝에서 직접 정리한다.
 *
 * H2가 아닌 실제 MySQL(Testcontainers)로 돈다: unique 제약 위반 시의 잠금/에러
 * 메시지 형식이 DB 엔진마다 달라서(AuthService가 MySQL의 실제 메시지 형식으로
 * 원인을 구분하므로), H2로는 이 테스트들이 검증하려는 걸 제대로 검증할 수 없다.
 * 또한 flyway를 켜서 V1/V2 마이그레이션이 실제로 적용되는지도 함께 검증한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql-test")
class AuthConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0.36");

    @DynamicPropertySource
    static void registerMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL_CONTAINER::getDriverClassName);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @MockitoBean
    EmailSender emailSender;

    @MockitoBean(name = "googleVerifier")
    SocialTokenVerifier socialTokenVerifier;

    @Test
    void 동시에_같은_refreshToken으로_재발급하면_하나만_성공한다() throws Exception {
        String email = "concurrent-refresh-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);

        mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"동시성테스터\"}"));

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(post("/api/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount = statusCodes.stream().filter(status -> status == 200).count();
        assertThat(successCount)
                .as("두 요청 중 정확히 하나만 재발급에 성공해야 한다. 실제 응답: %s", statusCodes)
                .isEqualTo(1);
    }

    @Test
    void 동시에_같은_이메일로_회원가입하면_하나만_성공하고_500은_없다() throws Exception {
        String email = "concurrent-signup-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(post("/api/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"동시성" + idx + "\"}"))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount = statusCodes.stream().filter(status -> status == 200).count();

        assertThat(successCount)
                .as("두 요청 중 정확히 하나만 회원가입에 성공해야 한다. 실제 응답: %s", statusCodes)
                .isEqualTo(1);
        assertThat(statusCodes)
                .as("나머지 하나는 409여야 하며 500이 나오면 안 된다. 실제 응답: %s", statusCodes)
                .doesNotContain(500)
                .contains(409);
    }

    @Test
    void 동시에_같은_닉네임으로_회원가입하면_하나만_성공하고_500은_없다() throws Exception {
        // 서로 다른 이메일 + 같은 닉네임으로 동시에 가입 요청을 보낸다.
        // existsByNickname 사전 검사는 두 스레드 모두 통과할 수 있으므로(TOCTOU),
        // 실제 경쟁 상태 방어가 uk_user_nickname 제약 + 예외 매핑에서 제대로
        // 동작하는지를 검증하는 테스트다.
        String email1 = "concurrent-nickname-1-" + System.currentTimeMillis() + "@example.com";
        String email2 = "concurrent-nickname-2-" + System.currentTimeMillis() + "@example.com";
        // nickname 검증 정책(2~10자)을 넘지 않도록 짧게 구성한다.
        // "동시성닉네임" + 전체 타임스탬프(13자리)를 쓰면 19자가 되어 @Valid 단계에서
        // 400으로 튕겨나가 버려서(테스트 자체가 서비스 로직까지 못 감), 타임스탬프를
        // 5자리로 잘라 총 7자(닉+5자리 숫자)로 맞춘다.
        String nickname = "닉" + (System.currentTimeMillis() % 100000);

        prepareVerifiedEmail(email1);
        prepareVerifiedEmail(email2);

        List<String> emails = List.of(email1, email2);
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            String email = emails.get(i);
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(post("/api/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"" + nickname + "\"}"))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                    bodies.add(result.getResponse().getContentAsString());
                } catch (Exception e) {
                    statusCodes.add(-1);
                    bodies.add(e.toString());
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount = statusCodes.stream().filter(status -> status == 200).count();

        assertThat(successCount)
                .as("두 요청 중 정확히 하나만 회원가입에 성공해야 한다. 실제 응답: %s / %s", statusCodes, bodies)
                .isEqualTo(1);
        assertThat(statusCodes)
                .as("나머지 하나는 409(USER_NICKNAME_ALREADY_EXISTS)여야 하며 500이 나오면 안 된다. 실제 응답: %s / %s", statusCodes, bodies)
                .doesNotContain(500)
                .contains(409);

        int conflictIdx = statusCodes.indexOf(409);
        assertThat(bodies.get(conflictIdx))
                .as("409 응답의 에러 코드는 USER_NICKNAME_ALREADY_EXISTS여야 한다 (email 중복으로 오분류되면 안 됨)")
                .contains("USER_NICKNAME_ALREADY_EXISTS");
    }

    @Test
    void 사전검사에서_닉네임_중복이면_바로_거부된다() throws Exception {
        String email1 = "nickname-check-1-" + System.currentTimeMillis() + "@example.com";
        String email2 = "nickname-check-2-" + System.currentTimeMillis() + "@example.com";
        String duplicateNickname = "중복될닉네임";

        prepareVerifiedEmail(email1);
        prepareVerifiedEmail(email2);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email1 + "\",\"password\":\"password1\",\"nickname\":\"" + duplicateNickname + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email2 + "\",\"password\":\"password1\",\"nickname\":\"" + duplicateNickname + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_ALREADY_EXISTS"));
    }

    private void prepareVerifiedEmail(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }
}