package Hampouch.server.domain.rest;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 스택(컨트롤러→서비스→JPA→H2) 통합 — 결과 화면 뒤 휴식 시작 → 휴식기 홈 → 더 쉬기 →
 * 새 챌린지 생성으로 휴식 자동 종료까지 한 흐름. 실제 HTTP 직렬화·검증·영속화를 한 번에 검증(MySQL 불필요).
 * 이 흐름이 부르는 경로는 전부 로그인이 필요해서 실제 액세스 토큰을 자체 발급해 부른다(JwtFilter까지 실제로 동작한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestFlowIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ChallengeRepository challengeRepository;
    @Autowired
    UserRestRepository userRestRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ChallengeService challengeService;
    @Autowired
    JwtProvider jwtProvider;

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    private Long newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocalUser(
                "rest-flow-" + suffix + "@hampouch.test", "encoded", "휴식" + suffix);
        return userRepository.save(user).getId();
    }

    @Test
    @DisplayName("챌린지가 끝난 유저가 휴식을 시작하고 복귀 예정일을 미룬 뒤 새 챌린지를 만들면 휴식이 자동으로 닫힌다")
    void restFlow() throws Exception {
        Long user = newUser();
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대(CI는 UTC)로 만들면 KST 새벽에 날짜가 갈라진다
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 0) 종료 챌린지를 심는다(종료 챌린지는 API로 못 만듦 — 히스토리 통합 테스트와 같은 이유).
        Challenge prev = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        prev.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(prev);

        // 1) 휴식 시작(7일) — 201, 시작일=오늘, 복귀 예정일=오늘+7
        mvc.perform(post("/api/rests")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.restStartDate").value(today.toString()))
                .andExpect(jsonPath("$.data.plannedResumeDate").value(today.plusDays(7).toString()));

        // 2) 휴식 중 또 시작하면 409
        mvc.perform(post("/api/rests")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REST_ALREADY_ACTIVE"));

        // 3) 오늘 챌린지가 없으므로 챌린지 현황은 빈 상태다
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.challenge", nullValue()))
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.consumption").doesNotExist());

        // 4) 더 쉬기(3일) — 복귀 예정일이 오늘+10으로 밀리고 휴식은 계속
        mvc.perform(post("/api/rests/resume")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"when\":\"EXTEND\",\"extendDays\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plannedResumeDate").value(today.plusDays(10).toString()))
                .andExpect(jsonPath("$.data.resumeDate").doesNotExist());

        // 5) 새 챌린지 생성 — 휴식이 오늘 날짜로 자동 종료되고 생성이 진행된다(배타 규칙)
        mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated());
        UserRest closed = userRestRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(user))
                .findFirst().orElseThrow();
        assertThat(closed.getActualResumeDate()).isEqualTo(today); // 자동 종료가 DB에 남았다
        assertThat(userRestRepository.findActiveOn(user, today)).isEmpty();

        // 6) 새 챌린지 현황이 조회된다
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000));

        // 7) 휴식이 닫혔으니 복귀 요청은 404, 챌린지가 진행 중이니 휴식 시작은 409
        mvc.perform(post("/api/rests/resume")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"when\":\"NOW\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REST_NOT_ACTIVE"));
        mvc.perform(post("/api/rests")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("복귀 예정일이 한참 지나도록 안 들어오던 유저가 돌아와 조금 더 쉬기를 고르면 새 복귀 예정일이 오늘 뒤로 잡힌다 — 지나간 예정일에 더하면 새 예정일도 과거라 복귀 팝업이 다시 떠서 더 쉬기가 무한 반복된다")
    void resumeExtendAfterLongAbsenceCountsFromToday() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 19일 전에 3일짜리 휴식을 걸어 예정일이 16일 전에 지나간 상태 — 복귀 API를 한 번도 안 불러 아직 활성이다
        userRestRepository.save(UserRest.start(user, today.minusDays(19), 3));

        mvc.perform(post("/api/rests/resume")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"when\":\"EXTEND\",\"extendDays\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plannedResumeDate").value(today.plusDays(3).toString()));

        UserRest extended = userRestRepository.findActiveOn(user, today).orElseThrow();
        assertThat(extended.getPlannedResumeDate()).isEqualTo(today.plusDays(3)); // 지나간 예정일 + 3일이 아니다
    }

    @Test
    @DisplayName("정기 확정 전에 기간이 끝난 IN_PROGRESS 상태가 남아 있어도 휴식 시작 경로가 결과를 확정해 409로 막히지 않는다")
    void restStartFinalizesPeriodEndedChallenge() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 10일 전 시작한 7일짜리 — 정기 확정 전에 IN_PROGRESS로 남은 상태
        Challenge periodEnded = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(today.minusDays(10))
                .budgetTotal(70000).dailyLimit(10000).build());

        mvc.perform(post("/api/rests")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isCreated()); // 기간이 끝난 챌린지가 409를 만들지 않는다

        Challenge finalized = challengeRepository.findById(periodEnded.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(ChallengeStatus.SUCCESS); // 7일은 3일 미입력 VOID 적용 대상이 아니다.
    }

    @Test
    @DisplayName("진행 중 챌린지 존재 판단을 다른 트랜잭션 없이 단독으로 불러도 기간 종료 결과가 DB에 실제로 저장된다 — 쓰기 트랜잭션 선언이 빠지면 확정이 조용히 증발하는 회귀 방지")
    void hasActiveChallengeStandaloneCommitsFinalization() {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Challenge periodEnded = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(today.minusDays(10))
                .budgetTotal(70000).dailyLimit(10000).build());

        // HTTP·외부 트랜잭션 없이 서비스 메서드를 직접 호출한다. 이 "단독"이 테스트의 핵심 —
        // 실제로는 hasActiveChallenge가 항상 @Transactional인 create()/휴식 start() 안에서 불려서,
        // 이 메서드의 @Transactional이 빠져도 바깥 트랜잭션이 더티 체킹을 대신 커밋해 버그가 가려진다.
        // 이 클래스는 @Transactional이 아니라(위 클래스 선언 확인) 바깥 트랜잭션이 없고,
        // 그래서 hasActiveChallenge 자기 자신의 쓰기 트랜잭션만이 유일한 경계가 된다.
        boolean active = challengeService.hasActiveChallenge(user);

        assertThat(active).isFalse();
        Challenge finalized = challengeRepository.findById(periodEnded.getId()).orElseThrow();
        // 반환값만이 아니라 새 조회로 다시 읽어 확인한다 — SUCCESS가 보이면 확정 UPDATE가 DB에 커밋된 것.
        // @Transactional이 없으면 확정은 준영속 엔티티에 남아 flush 없이 조용히 증발하고 여기서 IN_PROGRESS가 잡힌다.
        assertThat(finalized.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("지금 바로 복귀를 고르면 실제 복귀일이 오늘로 응답되고, 요청 종료 후 새 DB 조회에서도 오늘로 남아 활성 휴식에서 빠진다")
    void resumeNowCommitsActualResumeDate() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UserRest rest = userRestRepository.save(UserRest.start(user, today.minusDays(2), 7));

        mvc.perform(post("/api/rests/resume")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"when\":\"NOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeDate").value(today.toString()));

        // 복귀 기록은 save 없는 더티 체킹 UPDATE라 응답이 맞아도 커밋이 빠질 수 있다 — 새 조회로 저장 상태를 본다
        UserRest reloaded = userRestRepository.findById(rest.getId()).orElseThrow();
        assertThat(reloaded.getActualResumeDate()).isEqualTo(today);
        assertThat(userRestRepository.findActiveOn(user, today)).isEmpty();
    }

    @Test
    @DisplayName("내일 복귀를 고르면 실제 복귀일이 내일로 저장되고, 오늘은 아직 휴식 중이라 활성 휴식으로 계속 잡힌다")
    void resumeTomorrowCommitsActualResumeDate() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UserRest rest = userRestRepository.save(UserRest.start(user, today.minusDays(2), 7));

        mvc.perform(post("/api/rests/resume")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"when\":\"TOMORROW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeDate").value(today.plusDays(1).toString()));

        UserRest reloaded = userRestRepository.findById(rest.getId()).orElseThrow();
        assertThat(reloaded.getActualResumeDate()).isEqualTo(today.plusDays(1));
        // 내일 복귀 예약은 오늘까지 휴식이다 — 활성으로 남아 있어야 오늘 다시 골라 바꿀 수 있다
        assertThat(userRestRepository.findActiveOn(user, today)).isPresent();
    }

    @Test
    @DisplayName("액세스 토큰 없이 휴식 시작을 부르면 401과 인증 필요 에러 본문으로 거절된다")
    void restRejectsRequestWithoutToken() throws Exception {
        // 401이 나오는 자리는 둘이다. 여기서 보는 건 시큐리티 필터가 거절하는 쪽(AuthEntryPoint)이고,
        // 컨트롤러 테스트가 보는 건 그 필터를 꺼 둔 채 @LoginUserId 주입이 거절하는 쪽이다.
        // 두 응답의 본문이 같아야 안드가 한 가지로 처리한다.
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }
}
