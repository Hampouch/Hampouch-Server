package Hampouch.server.domain.rest;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.entity.UserRole;
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
 * 휴식 경로는 시큐리티 인증 예외 목록에 없어 실제 액세스 토큰을 자체 발급해 부른다(JwtFilter까지 실동작).
 * 챌린지 경로는 아직 X-User-Id 스텁 + 임시 인증 예외라 기존 헤더를 유지한다 — 챌린지 전환 이슈에서 함께 바뀔 부분.
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
    ChallengeService challengeService;
    @Autowired
    JwtProvider jwtProvider;

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    @Test
    @DisplayName("직전 챌린지가 끝난 유저가 휴식을 시작하면 홈이 휴식 화면으로 바뀌고, 유저가 조금 더 쉬기로 복귀 예정일을 미룬 뒤 새 챌린지를 만들면 휴식이 자동으로 닫히며 홈이 챌린지 화면으로 돌아온다 (통합)")
    void restFlow() throws Exception {
        // 챌린지·미니 통합 테스트(유저 1·4·5)와 데이터가 안 섞이게 전용 유저 사용
        Long user = 7L;
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대(CI는 UTC)로 만들면 KST 새벽에 날짜가 갈라진다
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 0) 직전 종료 챌린지를 심는다(종료 챌린지는 API로 못 만듦 — 히스토리 통합 테스트와 같은 이유).
        //    5/1~5/14, 한도 20000, 기록 0건 → 보관 중인 내 기록 = 절약 280,000(14일×20000 전액)·최고 연속 14
        Challenge prev = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(14).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(280000).dailyLimit(20000).build());
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

        // 3) 홈 현황이 404가 아니라 휴식기 홈 — challenge는 키를 생략하지 않고 null 값으로 실리고(안드의 휴식 모드 판별 신호), 직전 기록이 함께 실린다
        mvc.perform(get("/api/challenges/current").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.challenge", nullValue()))
                .andExpect(jsonPath("$.data.rest.restStartDate").value(today.toString()))
                .andExpect(jsonPath("$.data.rest.plannedResumeDate").value(today.plusDays(7).toString()))
                .andExpect(jsonPath("$.data.keptRecords.savedAmount").value(280000))
                .andExpect(jsonPath("$.data.keptRecords.maxStreak").value(14))
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
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated());
        UserRest closed = userRestRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(user))
                .findFirst().orElseThrow();
        assertThat(closed.getActualResumeDate()).isEqualTo(today); // 자동 종료가 DB에 남았다
        assertThat(userRestRepository.findActiveOn(user, today)).isEmpty();

        // 6) 홈은 챌린지 화면으로 복귀, 휴식 블록은 사라진다
        mvc.perform(get("/api/challenges/current").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.rest").doesNotExist())
                .andExpect(jsonPath("$.data.keptRecords").doesNotExist());

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
    @DisplayName("기간이 끝났는데 미확정으로 남은 챌린지가 있어도 휴식 시작이 409로 막히지 않고, 그 챌린지는 진행 중 챌린지가 있는지 확인하는 과정에서 확정돼 DB에 남는다 (통합)")
    void restStartFinalizesExpiredChallenge() throws Exception {
        Long user = 8L;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 10일 전 시작한 7일짜리 — 4일 전에 만료됐지만 결과 화면을 안 열어 IN_PROGRESS로 남은 상태
        Challenge expired = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(today.minusDays(10))
                .budgetTotal(70000).dailyLimit(10000).build());

        mvc.perform(post("/api/rests")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isCreated()); // 만료 챌린지가 409를 만들지 않는다

        Challenge finalized = challengeRepository.findById(expired.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(ChallengeStatus.SUCCESS); // 기록 0건 = 전일 미입력 = 성공으로 확정
    }

    @Test
    @DisplayName("진행 중 챌린지 존재 판단을 다른 트랜잭션 없이 단독으로 불러도 만료 챌린지 확정이 DB에 실제로 저장된다 — 쓰기 트랜잭션 선언이 빠지면 확정이 조용히 증발하는 회귀 방지")
    void hasActiveChallengeStandaloneCommitsFinalization() {
        Long user = 9L;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Challenge expired = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(today.minusDays(10))
                .budgetTotal(70000).dailyLimit(10000).build());

        // HTTP·외부 트랜잭션 없이 서비스 메서드를 직접 호출한다. 이 "단독"이 테스트의 핵심 —
        // 실제로는 hasActiveChallenge가 항상 @Transactional인 create()/휴식 start() 안에서 불려서,
        // 이 메서드의 @Transactional이 빠져도 바깥 트랜잭션이 더티 체킹을 대신 커밋해 버그가 가려진다.
        // 이 클래스는 @Transactional이 아니라(위 클래스 선언 확인) 바깥 트랜잭션이 없고,
        // 그래서 hasActiveChallenge 자기 자신의 쓰기 트랜잭션만이 유일한 경계가 된다.
        boolean active = challengeService.hasActiveChallenge(user);

        assertThat(active).isFalse();
        Challenge finalized = challengeRepository.findById(expired.getId()).orElseThrow();
        // 반환값만이 아니라 새 조회로 다시 읽어 확인한다 — SUCCESS가 보이면 확정 UPDATE가 DB에 커밋된 것.
        // @Transactional이 없으면 확정은 준영속 엔티티에 남아 flush 없이 조용히 증발하고 여기서 IN_PROGRESS가 잡힌다.
        assertThat(finalized.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("액세스 토큰 없이 휴식 시작을 부르면 401과 인증 필요 에러 본문으로 거절된다 — 휴식 경로는 시큐리티 인증 예외 목록에 없어서 컨트롤러에 닿기 전에 필터 단계에서 막힌다 (통합)")
    void restRejectsRequestWithoutToken() throws Exception {
        // 컨트롤러 테스트의 401(리졸버 경로)과 별개인 필터 경로(AuthEntryPoint) — 두 401의 본문이 같아야 안드가 한 가지로 처리한다
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restDays\":7}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }
}
