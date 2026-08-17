package Hampouch.server.domain.challenge;

import Hampouch.server.domain.challenge.entity.*;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.service.ChallengeFinalizationScheduler;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 스택(컨트롤러→서비스→JPA→H2) 통합 — 생성 → 일별 입력 → 현황 → 캘린더 happy path.
 * 실제 HTTP 직렬화·검증·영속화를 한 번에 검증(MySQL 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChallengeFlowIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    ChallengeRepository challengeRepository;
    @Autowired
    ChallengeDayRepository challengeDayRepository;
    @Autowired
    ChallengeFinalizationScheduler challengeFinalizationScheduler;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JwtProvider jwtProvider;

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    private Long newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocalUser(
                "challenge-flow-" + suffix + "@hampouch.test", "encoded", "챌린지" + suffix);
        return userRepository.save(user).getId();
    }

    @Test
    @DisplayName("액세스 토큰 없이 홈의 진행 중 챌린지 조회를 부르면 401과 인증 필요 에러 본문으로 거절된다")
    void challengeRejectsRequestWithoutToken() throws Exception {
        // 401이 나오는 자리는 둘이다. 여기서 보는 건 시큐리티 필터가 거절하는 쪽(AuthEntryPoint)이고,
        // 컨트롤러 테스트가 보는 건 그 필터를 꺼 둔 채 @LoginUserId 주입이 거절하는 쪽이다.
        // 두 응답의 본문이 같아야 안드가 한 가지로 처리한다.
        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("액세스 토큰 없이 목표 금액 조정을 부르면 401로 거절된다")
    void adjustRejectsRequestWithoutToken() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_10" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("목표 금액을 조정하면 목표와 앞으로의 하루 한도가 함께 오르고 이미 초과로 판정된 지난 날의 기록은 그대로 남는다")
    void adjustRaisesLimitWithoutRewritingPastDays() throws Exception {
        long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 지난 날이 있어야 소급 여부를 볼 수 있어 시작일이 과거인 챌린지를 직접 넣는다(생성 API는 @FutureOrPresent라 과거 시작을 막는다)
        Challenge ch = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(30).startDate(today.minusDays(5))
                .budgetTotal(300000).dailyLimit(10000).build());
        ChallengeDay pastDay = challengeDayRepository.save(ChallengeDay.of(
                ch, today.minusDays(3), 12000, DayStatus.OVER, 10000));

        mvc.perform(post("/api/challenges/" + ch.getId() + "/adjust")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_30" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetTotal").value(390000)) // 300000 × 1.3 — 배율은 목표에 붙는다
                .andExpect(jsonPath("$.data.dailyLimit").value(13000))   // 390000 ÷ 30 — 하루는 파생
                .andExpect(jsonPath("$.data.usedCount").value(1))
                .andExpect(jsonPath("$.data.maxCount").value(2)); // 30일 = 2회

        // 새 목표·한도가 홈에 즉시 반영되고 조정 현황도 같이 올라간다
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.budgetTotal").value(390000))
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(13000))
                .andExpect(jsonPath("$.data.adjustment.usedCount").value(1))
                .andExpect(jsonPath("$.data.adjustment.maxCount").value(2));

        // 지난 날은 옛 한도 스냅샷과 초과 판정을 그대로 유지 — 새 한도(13000)로 다시 쟀다면 SUCCESS가 됐을 지출이다
        ChallengeDay reloaded = challengeDayRepository.findById(pastDay.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DayStatus.OVER);
        assertThat(reloaded.getDailyLimit()).isEqualTo(10000);
    }

    @Test
    @DisplayName("과거 날짜 홈은 그날의 종료 챌린지와 지표를 반환하고 챌린지가 없던 날은 명시적인 빈 상태를 반환한다")
    void historicalHomeFlow() throws Exception {
        long user = newUser();
        long otherUser = newUser();
        LocalDate selectedDate = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(5);
        LocalDate startDate = selectedDate.minusDays(2);

        Challenge challenge = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(5).startDate(startDate)
                .budgetTotal(50000).dailyLimit(10000).build());
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(challenge);
        challengeDayRepository.save(ChallengeDay.of(
                challenge, selectedDate, 7000, DayStatus.SUCCESS, 10000));

        Challenge others = challengeRepository.save(Challenge.builder()
                .userId(otherUser).durationDays(5).startDate(startDate)
                .budgetTotal(50000).dailyLimit(10000).build());
        others.applyResult(ChallengeStatus.FAIL);
        challengeRepository.save(others);

        mvc.perform(get("/api/challenges/current")
                        .header("Authorization", bearer(user))
                        .param("date", selectedDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.id").value(challenge.getId()))
                .andExpect(jsonPath("$.data.challenge.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.progress.elapsedDays").value(3))
                .andExpect(jsonPath("$.data.progress.remainingDays").value(2))
                .andExpect(jsonPath("$.data.progress.currentStreak").value(3))
                .andExpect(jsonPath("$.data.progress.savedAmountSoFar").value(23000))
                .andExpect(jsonPath("$.data.consumption.todaySpent").value(7000))
                .andExpect(jsonPath("$.data.consumption.todayRemaining").value(3000));

        mvc.perform(get("/api/challenges/current")
                        .header("Authorization", bearer(user))
                        .param("date", selectedDate.minusDays(10).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.challenge", nullValue()))
                .andExpect(jsonPath("$.data.rest").doesNotExist())
                .andExpect(jsonPath("$.data.progress").doesNotExist());

        mvc.perform(get("/api/challenges/current")
                        .header("Authorization", bearer(user))
                        .param("date", LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("생성부터 일별 입력(성공·초과), 현황, 캘린더 조회까지 전체 흐름이 실제 스택으로 끝까지 동작한다")
    void fullFlow() throws Exception {
        Long user = newUser();
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대(CI는 UTC)로 만들면 KST 새벽(00~09시)에
        // 두 날짜가 갈라져, 아래 3)의 "내일 기록은 집계 미포함" 전제가 깨진다(내일이 서버의 오늘이 됨). 미니 통합과 동일 처리.
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")); // @FutureOrPresent 통과
        LocalDate day2 = start.plusDays(1);

        // 1) 생성: 7일 / 70,000 → dailyLimit 10,000
        String created = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();

        // 2) 일별 입력: 성공 1일(8,000) + 초과 1일(15,000)
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000));
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day2 + "\",\"spentAmount\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVER"));

        // 3) 현황: 응답 형태 {code, message, data:{challenge, progress, ...}} + 집계.
        //    홈 집계는 판정 완료 구간(시작~오늘)까지만(0714 확정) — 내일(day2)의 초과 기록은 아직 미포함.
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.challenge.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.progress.successDays").value(1))
                .andExpect(jsonPath("$.data.progress.overDays").value(0))
                .andExpect(jsonPath("$.data.progress.savedAmountSoFar").value(2000));

        // 4) 캘린더: 이번 달에 시작일 기록(SUCCESS) 포함
        mvc.perform(get("/api/challenges/" + id + "/calendar")
                        .header("Authorization", bearer(user))
                        .param("year", String.valueOf(start.getYear()))
                        .param("month", String.valueOf(start.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[?(@.date=='" + start + "')].status", contains("SUCCESS")));
    }

    @Test
    @DisplayName("기간이 끝난 챌린지는 최종 종료를 누르기 전까지 일별 기록을 고칠 수 있고, 누르는 순간 성패가 확정되며 그 뒤의 기록 수정과 재종료는 409로 막힌다")
    void closeFinalizesResultAndLocksRecords() throws Exception {
        long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 기간이 끝난 챌린지는 생성 API로 못 만든다(통합 테스트는 시계를 못 돌림) → 어제 끝난 챌린지를 직접 심는다
        Challenge ch = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(today.minusDays(7))
                .budgetTotal(70000).dailyLimit(10000).build());
        LocalDate lastDay = today.minusDays(1);

        // 결과 팝업의 [지출 수정하기] 구간 — 기간은 끝났지만 아직 안 잠겨 기록 수정이 통한다
        mvc.perform(post("/api/challenges/" + ch.getId() + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + lastDay + "\",\"spentAmount\":9000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // [챌린지 종료] — 결과 화면을 한 번도 안 열었어도 여기서 성패가 확정된다
        mvc.perform(post("/api/challenges/" + ch.getId() + "/close").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS")) // 총지출 9000 ≤ 목표 70000
                .andExpect(jsonPath("$.data.expenseLockedAt").exists());

        mvc.perform(post("/api/challenges/" + ch.getId() + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + lastDay + "\",\"spentAmount\":99000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_CLOSED"));

        mvc.perform(post("/api/challenges/" + ch.getId() + "/close").header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_CLOSED"));

        // 결과 조회에 종료 시각이 실린다 — 클라는 이 값으로 종료 팝업을 다시 띄울지 정한다
        mvc.perform(get("/api/challenges/" + ch.getId() + "/result").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseLockedAt").exists());

        Challenge reloaded = challengeRepository.findById(ch.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(reloaded.getExpenseLockedAt()).isNotNull();
        assertThat(challengeDayRepository.findByChallenge_IdAndDayDate(ch.getId(), lastDay).orElseThrow()
                .getSpentAmount()).isEqualTo(9000); // 잠긴 뒤 요청이 기록을 못 바꿨다

        // 최종 종료한 뒤에는 새 챌린지를 시작할 수 있다
        mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("기간이 종료된 8일 챌린지의 마지막 3일이 미입력이면 최종 종료 요청은 409이고, 자동 취소 상태는 저장되지만 expenseLockedAt은 null이다")
    void closeRequestPersistsAutoCancellationAfterRejection() throws Exception {
        long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Challenge challenge = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(8).startDate(today.minusDays(8))
                .budgetTotal(80000).dailyLimit(10000).build());

        mvc.perform(post("/api/challenges/" + challenge.getId() + "/close")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_CLOSABLE"));

        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(reloaded.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(reloaded.isExpenseLocked()).isFalse();
    }

    @Test
    @DisplayName("현재 챌린지 조회가 3일 연속 미입력을 감지하면 요청 종료 후 DB에도 자동 취소 상태가 남는다")
    void currentAutoCancelCommitsAfterRequest() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Challenge challenge = challengeRepository.save(Challenge.builder()
                .userId(user)
                .durationDays(14)
                .startDate(today.minusDays(3))
                .budgetTotal(140000)
                .dailyLimit(10000)
                .build());

        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseInputState").value("AUTO_CANCELLED"))
                .andExpect(jsonPath("$.data.challenge.status").value("VOID"));

        Challenge reloaded = challengeRepository.findById(challenge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(reloaded.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
    }

    @Test
    @DisplayName("정기 확정 뒤 지난 챌린지 목록이 종료된 챌린지의 총지출과 절약액을 내려준다")
    void historyFlow() throws Exception {
        // 종료된 챌린지는 API로 못 만든다(기간 경과가 필요한데 통합 테스트는 시계를 못 돌림) → 리포지토리로 직접 심는다.
        Long user = newUser();

        // 5/1~5/7 SUCCESS 종료, 지출 0원 → 각 날짜를 0원으로 집계
        Challenge success = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        success.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(success);

        // 6/1~6/7 FAIL 종료, 6/3에 15000 초과 기록 1건 → 나머지 미기록일은 0원
        Challenge fail = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        challengeDayRepository.save(ChallengeDay.of(fail, LocalDate.of(2026, 6, 3), 15000, DayStatus.OVER, fail.getDailyLimit()));
        fail.applyResult(ChallengeStatus.FAIL);
        challengeRepository.save(fail);

        // 6/20~6/26 챌린지는 기간 종료 후 정기 작업이 총지출을 기준으로 확정한다.
        Challenge periodEnded = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 20))
                .budgetTotal(70000).dailyLimit(10000).build());

        challengeFinalizationScheduler.finalizeDueChallenges(LocalDate.now(ZoneId.of("Asia/Seoul")));

        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                // 최근 종료 순: 정기 확정(6/26) → FAIL(6/7) → SUCCESS(5/14)
                .andExpect(jsonPath("$.data.items[0].challengeId").value(periodEnded.getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].savedAmount").value(70000))
                .andExpect(jsonPath("$.data.items[1].challengeId").value(fail.getId()))
                .andExpect(jsonPath("$.data.items[1].status").value("FAIL"))
                .andExpect(jsonPath("$.data.items[1].actualSpent").value(15000))
                .andExpect(jsonPath("$.data.items[1].savedAmount").value(60000))
                .andExpect(jsonPath("$.data.items[2].challengeId").value(success.getId()))
                .andExpect(jsonPath("$.data.items[2].actualSpent").value(0))
                .andExpect(jsonPath("$.data.items[2].savedAmount").value(70000));

        // 정기 확정이 DB에 실제로 남았는지 확인한다.
        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));
    }

    @Test
    @DisplayName("집중 카테고리 입력이 제거된 뒤에도 옛 앱이 생성 요청에 weakCategories 필드를 담아 보내면 필드만 무시되고 생성은 그대로 성공한다")
    void create_ignoresLegacyWeakCategoriesField() throws Exception {
        Long user = newUser();
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")); // 위 테스트들과 동일 처리(서버의 "오늘" = Asia/Seoul)

        mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start
                                + "\",\"weakCategories\":[\"배달\",\"카페\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("진행 중 챌린지를 포기하면 즉시 FAIL로 확정돼 현재 챌린지 조회에서 빈 상태가 되고 결과 조회가 열리며, 이후 지출을 고쳐도 SUCCESS로 되살아나지 않는다")
    void giveUpFlow() throws Exception {
        Long user = newUser();
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대(CI는 UTC)로 만들면 KST 새벽(00~09시)에
        // 두 날짜가 갈라져 @FutureOrPresent·기간 검증이 흔들린다. 미니 통합 테스트와 동일 처리.
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 1) 생성(7일/70,000 → 한도 10,000) + 오늘 초과 기록 1건(15,000)
        String created = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":15000}"))
                .andExpect(status().isOk());

        // 2) 포기: end_date 경과와 무관하게 즉시 FAIL 확정
        mvc.perform(post("/api/challenges/" + id + "/give-up").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(id))
                .andExpect(jsonPath("$.data.status").value("FAIL"));

        // 3) 현재 챌린지 조회는 빈 상태를 반환하고, 같은 챌린지를 다시 포기하면 409
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challenge", nullValue()));
        mvc.perform(post("/api/challenges/" + id + "/give-up").header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_IN_PROGRESS"));

        // 4) 종료일 전이라도 결과 조회가 열리고 포기한 오늘은 금액 집계에서 제외된다.
        mvc.perform(get("/api/challenges/" + id + "/result").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.summary.actualSpent").value(0))
                .andExpect(jsonPath("$.data.summary.savedAmount").value(0));

        // 5) 포기일 기록을 수정하면 그날 판정은 바뀌지만 결과·히스토리 금액에는 포함되지 않고 FAIL도 유지된다.
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].challengeId").value(id))
                .andExpect(jsonPath("$.data.items[0].status").value("FAIL"))
                .andExpect(jsonPath("$.data.items[0].actualSpent").value(0))
                .andExpect(jsonPath("$.data.items[0].savedAmount").value(0));
    }

    @Test
    @DisplayName("포기한 날 새 챌린지를 반복해서 시작하면 당일 기록 한 건이 새 챌린지로 이동하고 새 하루 한도로 다시 판정된다")
    void sameDayRestartTransfersDayRecordToLatestChallenge() throws Exception {
        Long user = newUser();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        String firstCreated = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = om.readTree(firstCreated).path("data").path("challengeId").asLong();
        mvc.perform(post("/api/challenges/" + firstId + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + today + "\",\"spentAmount\":12000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVER"));
        Long dayId = challengeDayRepository.findByChallenge_IdAndDayDate(firstId, today).orElseThrow().getId();
        mvc.perform(post("/api/challenges/" + firstId + "/give-up")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        String secondCreated = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":105000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dailyLimit").value(15000))
                .andReturn().getResponse().getContentAsString();
        long secondId = om.readTree(secondCreated).path("data").path("challengeId").asLong();

        ChallengeDay secondDay = challengeDayRepository.findById(dayId).orElseThrow();
        assertThat(secondDay.getChallenge().getId()).isEqualTo(secondId);
        assertThat(secondDay.getSpentAmount()).isEqualTo(12000);
        assertThat(secondDay.getDailyLimit()).isEqualTo(15000);
        assertThat(secondDay.getStatus()).isEqualTo(DayStatus.SUCCESS);
        assertThat(challengeDayRepository.findByChallenge_IdAndDayDate(firstId, today)).isEmpty();

        mvc.perform(post("/api/challenges/" + secondId + "/give-up")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        String thirdCreated = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":56000,\"startDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dailyLimit").value(8000))
                .andReturn().getResponse().getContentAsString();
        long thirdId = om.readTree(thirdCreated).path("data").path("challengeId").asLong();

        ChallengeDay thirdDay = challengeDayRepository.findById(dayId).orElseThrow();
        assertThat(thirdDay.getChallenge().getId()).isEqualTo(thirdId);
        assertThat(thirdDay.getSpentAmount()).isEqualTo(12000);
        assertThat(thirdDay.getDailyLimit()).isEqualTo(8000);
        assertThat(thirdDay.getStatus()).isEqualTo(DayStatus.OVER);
        assertThat(challengeDayRepository.findByChallenge_IdAndDayDate(secondId, today)).isEmpty();

        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.id").value(thirdId))
                .andExpect(jsonPath("$.data.consumption.todaySpent").value(12000))
                .andExpect(jsonPath("$.data.consumption.dailyLimit").value(8000));
    }

    @Test
    @DisplayName("기간이 종료된 챌린지의 결과 조회가 총지출이 목표를 넘으면 FAIL로, 넘지 않으면 SUCCESS로 확정하고 그 상태가 요청 종료 후 새 DB 조회에서도 남아 있다")
    void resultFinalizationCommitsAfterRequest() throws Exception {
        Long user = newUser();

        // 6/1~6/7 목표 70,000 — 6/3 하루 80,000 지출로 총지출이 목표를 넘어 FAIL감
        Challenge fail = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        challengeDayRepository.save(ChallengeDay.of(fail, LocalDate.of(2026, 6, 3), 80000, DayStatus.OVER, 10000));

        mvc.perform(get("/api/challenges/" + fail.getId() + "/result").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAIL"));
        // 확정은 save 없는 더티 체킹 UPDATE라 응답이 맞아도 커밋이 빠질 수 있다 — 새 조회로 저장 상태를 본다
        assertThat(challengeRepository.findById(fail.getId()).orElseThrow().getStatus())
                .isEqualTo(ChallengeStatus.FAIL);

        // 앞 챌린지가 FAIL로 굳어 진행 중 유니크 자리가 비었으니 같은 유저에게 두 번째를 심을 수 있다
        Challenge success = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 7, 1))
                .budgetTotal(70000).dailyLimit(10000).build());

        mvc.perform(get("/api/challenges/" + success.getId() + "/result").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertThat(challengeRepository.findById(success.getId()).orElseThrow().getStatus())
                .isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("같은 날짜로 일별 지출을 다시 보내면 새 행 없이 기존 기록이 고쳐지고, 바뀐 금액과 판정이 요청 종료 후 새 DB 조회에서도 남아 있다")
    void upsertDayUpdateCommitsAfterRequest() throws Exception {
        Long user = newUser();
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")); // 서버의 "오늘"(Asia/Seoul) — 위 테스트들과 동일 처리

        String created = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();

        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        Long rowId = challengeDayRepository.findByChallenge_IdAndDayDate(id, start).orElseThrow().getId();

        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVER"));

        // 두 번째 보내기는 INSERT가 아니라 save 없는 더티 체킹 UPDATE — 커밋 여부는 새 조회로만 보인다
        ChallengeDay reloaded = challengeDayRepository.findByChallenge_IdAndDayDate(id, start).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(rowId); // 새 행이 생긴 게 아니라 기존 행이 고쳐졌다
        assertThat(reloaded.getSpentAmount()).isEqualTo(15000);
        assertThat(reloaded.getStatus()).isEqualTo(DayStatus.OVER);
    }

    @Test
    @DisplayName("기간 종료 후 FAIL로 확정된 챌린지의 지출을 고쳐 총지출이 목표 안으로 들어오면 결과가 SUCCESS로 재계산되고, 그 재계산이 요청 종료 후 새 DB 조회에서도 남아 있다")
    void dayEditRecalculationCommitsAfterRequest() throws Exception {
        Long user = newUser();
        LocalDate overDay = LocalDate.of(2026, 6, 3);

        // 6/1~6/7 목표 70,000에 6/3 80,000 지출 → 기간 종료 후 FAIL로 확정된 상태를 심는다.
        // applyResult로 굳힌 FAIL은 endReason이 없어 재계산 대상이다 — 선언으로 남는 포기·자동취소와 갈라지는 지점.
        Challenge ch = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        challengeDayRepository.save(ChallengeDay.of(ch, overDay, 80000, DayStatus.OVER, 10000));
        ch.applyResult(ChallengeStatus.FAIL);
        challengeRepository.save(ch);

        // 종료 후 지출 수정(0711 확정 경로) — 80,000을 5,000으로 고치면 총지출이 목표 아래로 내려간다
        mvc.perform(post("/api/challenges/" + ch.getId() + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + overDay + "\",\"spentAmount\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 기록 수정과 결과 뒤집기 둘 다 더티 체킹 UPDATE — 커밋 여부는 새 조회로만 보인다
        assertThat(challengeRepository.findById(ch.getId()).orElseThrow().getStatus())
                .isEqualTo(ChallengeStatus.SUCCESS);
        assertThat(challengeDayRepository.findByChallenge_IdAndDayDate(ch.getId(), overDay).orElseThrow()
                .getSpentAmount()).isEqualTo(5000);
    }
}
