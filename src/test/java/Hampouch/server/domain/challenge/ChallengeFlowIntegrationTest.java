package Hampouch.server.domain.challenge;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
    // 집중 카테고리는 리포지토리도 조회 API도 없어 저장된 행을 직접 읽는다
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtProvider jwtProvider;

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    @Test
    @DisplayName("액세스 토큰 없이 홈 현황을 부르면 401과 인증 필요 에러 본문으로 거절된다 — 챌린지 경로가 시큐리티 인증 예외 목록에서 빠져 컨트롤러에 닿기 전에 필터 단계에서 막힌다 (통합)")
    void challengeRejectsRequestWithoutToken() throws Exception {
        // 컨트롤러 테스트의 401(리졸버 경로)과 별개인 필터 경로(AuthEntryPoint) — 두 401의 본문이 같아야 안드가 한 가지로 처리한다
        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("생성부터 일별 입력(성공·초과), 현황, 캘린더 조회까지 전체 흐름이 실제 스택으로 끝까지 동작한다 (통합)")
    void fullFlow() throws Exception {
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대(CI는 UTC)로 만들면 KST 새벽(00~09시)에
        // 두 날짜가 갈라져, 아래 3)의 "내일 기록은 집계 미포함" 전제가 깨진다(내일이 서버의 오늘이 됨). 미니 통합과 동일 처리.
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")); // @FutureOrPresent 통과
        LocalDate day2 = start.plusDays(1);

        // 1) 생성: 7일 / 70,000 → dailyLimit 10,000
        String created = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start
                                + "\",\"weakCategories\":[\"카페음료\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();

        // 2) 일별 입력: 성공 1일(8,000) + 초과 1일(15,000)
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000));
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day2 + "\",\"spentAmount\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVER"));

        // 3) 현황: 응답 형태 {code, message, data:{challenge, progress, ...}} + 집계.
        //    홈 집계는 판정 완료 구간(시작~오늘)까지만(0714 확정) — 내일(day2)의 초과 기록은 아직 미포함.
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.challenge.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.progress.successDays").value(1))
                .andExpect(jsonPath("$.data.progress.overDays").value(0))
                .andExpect(jsonPath("$.data.progress.savedAmountSoFar").value(2000));

        // 4) 캘린더: 이번 달에 시작일 기록(SUCCESS) 포함
        mvc.perform(get("/api/challenges/" + id + "/calendar")
                        .header("Authorization", bearer(1L))
                        .param("year", String.valueOf(start.getYear()))
                        .param("month", String.valueOf(start.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[?(@.date=='" + start + "')].status", contains("SUCCESS")));
    }

    @Test
    @DisplayName("지난 챌린지 리스트가 실제 스택에서 종료된 것만 최근 종료 순으로 집계와 함께 내려오고, 만료 후 미확정 챌린지도 조회 시점에 확정돼 실린다 (통합)")
    void historyFlow() throws Exception {
        // 종료된 챌린지는 API로 못 만든다(기간 경과가 필요한데 통합 테스트는 시계를 못 돌림) → 리포지토리로 직접 심는다.
        // fullFlow(유저 1)와 데이터가 안 섞이게 전용 유저(4) 사용 — 미니 통합 테스트와 같은 관례.
        Long user = 4L;

        // 5/1~5/14 SUCCESS 종료, 기록 0건 → actualSpent 0, savedAmount = 14일 × 20000 전액
        Challenge success = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(14).startDate(LocalDate.of(2026, 5, 1))
                .budgetTotal(280000).dailyLimit(20000).build());
        success.applyResult(ChallengeStatus.SUCCESS);
        challengeRepository.save(success);

        // 6/1~6/7 FAIL 종료, 6/3에 15000 초과 기록 1건 → actualSpent 15000, savedAmount = 6일 × 10000
        Challenge fail = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(70000).dailyLimit(10000).build());
        challengeDayRepository.save(ChallengeDay.of(fail, LocalDate.of(2026, 6, 3), 15000, DayStatus.OVER));
        fail.applyResult(ChallengeStatus.FAIL);
        challengeRepository.save(fail);

        // 6/20~6/26에 만료됐지만 결과 화면을 안 열어 IN_PROGRESS로 남은 챌린지
        // → 히스토리 조회가 lazy 확정(기록 0건 = 전일 미입력 = SUCCESS)해서 리스트에 실려야 한다
        Challenge expired = challengeRepository.save(Challenge.builder()
                .userId(user).durationDays(7).startDate(LocalDate.of(2026, 6, 20))
                .budgetTotal(70000).dailyLimit(10000).build());

        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                // 최근 종료 순: 만료-미확정(6/26) → FAIL(6/7) → SUCCESS(5/14)
                .andExpect(jsonPath("$.data.items[0].challengeId").value(expired.getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].savedAmount").value(70000))
                .andExpect(jsonPath("$.data.items[1].challengeId").value(fail.getId()))
                .andExpect(jsonPath("$.data.items[1].status").value("FAIL"))
                .andExpect(jsonPath("$.data.items[1].actualSpent").value(15000))
                .andExpect(jsonPath("$.data.items[1].savedAmount").value(60000))
                .andExpect(jsonPath("$.data.items[2].challengeId").value(success.getId()))
                .andExpect(jsonPath("$.data.items[2].actualSpent").value(0))
                .andExpect(jsonPath("$.data.items[2].savedAmount").value(280000));

        // 확정이 DB에 실제로 남았는지 — 다음 조회부터는 lazy 확정 없이도 종료로 잡힌다
        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));
    }

    @Test
    @DisplayName("집중 카테고리를 교체하면 저장돼 있던 카테고리 행이 실제로 지워지고 요청에 담아 보낸 카테고리만 행으로 남으며, 교체 전후에 겹치는 카테고리가 있어도 같은 챌린지에 같은 카테고리 두 줄을 막는 제약에 걸리지 않는다 (통합)")
    void focusCategoriesFlow() throws Exception {
        // 앞의 세 테스트(유저 1·4·5)와 데이터가 안 섞이게 전용 유저 사용
        Long user = 6L;
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")); // 위 테스트들과 동일 처리(서버의 "오늘" = Asia/Seoul)

        // 1) 온보딩에서 고른 집중 카테고리 2개로 생성
        String created = mvc.perform(post("/api/challenges")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start
                                + "\",\"weakCategories\":[\"배달\",\"카페\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();
        assertThat(savedCategories(id)).containsExactly("배달", "카페");
        Long keptRowId = savedRowId(id, "카페"); // 아래 교체에서 살아남아야 하는 행

        // 2) 교체 — 겹치는 "카페"가 살아남고 요청 안의 중복도 접혀야 한다(어긋나면 유니크 제약으로 500)
        String replace = "{\"categories\":[\"카페\",\"편의점\",\"카페\"]}";
        mvc.perform(put("/api/challenges/" + id + "/focus-categories")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(id))
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.categories[0]").value("카페"))
                .andExpect(jsonPath("$.data.categories[1]").value("편의점"));
        assertThat(savedCategories(id)).containsExactly("카페", "편의점");
        // 겹치는 "카페"는 지웠다 새로 넣은 게 아니라 원래 행 그대로 — 재사용이 구현의 핵심
        assertThat(savedRowId(id, "카페")).isEqualTo(keptRowId);

        // 3) 같은 요청을 다시 보내도 결과가 같다
        mvc.perform(put("/api/challenges/" + id + "/focus-categories")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replace))
                .andExpect(status().isOk());
        assertThat(savedCategories(id)).containsExactly("카페", "편의점");

        // 4) 빈 목록이면 전부 해제(에러가 아니다)
        mvc.perform(put("/api/challenges/" + id + "/focus-categories")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isEmpty());
        assertThat(savedCategories(id)).isEmpty();
    }

    private List<String> savedCategories(long challengeId) {
        return jdbc.queryForList(
                "select category from challenge_weak_category where challenge_id = ? order by id",
                String.class, challengeId);
    }

    private Long savedRowId(long challengeId, String category) {
        return jdbc.queryForObject(
                "select id from challenge_weak_category where challenge_id = ? and category = ?",
                Long.class, challengeId, category);
    }

    @Test
    @DisplayName("진행 중 챌린지를 포기하면 즉시 FAIL로 확정돼 홈에서 사라지고 결과 조회가 열리며, 이후 지출을 고쳐도 SUCCESS로 되살아나지 않는다 (통합)")
    void giveUpFlow() throws Exception {
        // fullFlow(유저 1)·historyFlow(유저 4)와 데이터가 안 섞이게 전용 유저 사용
        Long user = 5L;
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

        // 3) 홈 현황에서 사라짐(진행 중 없음 404) + 같은 챌린지를 다시 포기하면 409
        mvc.perform(get("/api/challenges/current").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/challenges/" + id + "/give-up").header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_IN_PROGRESS"));

        // 4) 종료일 전이라도 결과 조회가 열린다(§4 status 규칙의 예외 경로).
        //    금액 집계 필드는 단정하지 않는다 — 포기 챌린지의 집계 구간이 명세 공백(질문 13)이라 잠금은 답 이후에.
        mvc.perform(get("/api/challenges/" + id + "/result").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAIL"));

        // 5) 기간 내 지출 수정은 여전히 허용(0711 "종료 후 자유 수정")되고 그날 판정도 새 금액 기준으로 바뀌지만,
        //    기록상 전원 성공이 돼도 포기 FAIL은 유저 선언이라 재계산으로 부활하지 않는다 — 히스토리에도 FAIL로 남는다
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mvc.perform(get("/api/challenges/history").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].challengeId").value(id))
                .andExpect(jsonPath("$.data.items[0].status").value("FAIL"));
    }
}
