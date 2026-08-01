package Hampouch.server.domain.challenge.controller;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요.
 * 인증은 매 테스트 전 컨텍스트에 직접 세팅한다 — .with(authentication(...)) 방식은 시큐리티 필터가
 * 옮겨 줘야 작동해서 필터를 꺼 둔(addFilters=false) 이 슬라이스에선 401이 난다.
 */
@WebMvcTest(ChallengeController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class ChallengeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChallengeService service;

    @MockitoBean
    JwtProvider jwtProvider; // JwtFilter가 Filter 타입이라 슬라이스 컨텍스트에 자동 포함되며 요구하는 의존성

    /** 리졸버가 통과시키는 principal은 Long뿐 — JwtFilter가 넣는 것과 같은 모양으로 세팅한다. */
    @BeforeEach
    void loginAsUser1() {
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    /** 컨텍스트는 스레드에 남으므로 비워 준다 — 안 비우면 같은 스레드를 쓰는 다음 테스트 클래스로 로그인이 샌다. */
    @AfterEach
    void clearLogin() {
        TestSecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 정보 없이 챌린지 생성을 요청하면 401과 인증 필요 에러 본문으로 거절된다 — 요청 본문 검증보다 유저 식별이 먼저라, 본문이 틀려도 400이 아니라 401이 나간다")
    void create_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext(); // 공통 준비가 넣어 둔 로그인 상태를 이 테스트만 되돌린다
        // 일부러 검증에도 걸리는 본문(durationDays 0) — 유저 식별이 본문 검증보다 먼저임을 응답 코드로 증명
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 0, "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("생성 요청이 정상이면 201 Created와 Location 헤더, 생성 결과 본문을 돌려준다")
    void create_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new CreateChallengeResponse(
                1L, 3333, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 30), ChallengeStatus.IN_PROGRESS));

        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/challenges/1"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(1))
                .andExpect(jsonPath("$.data.dailyLimit").value(3333));
    }

    @Test
    @DisplayName("챌린지 기간(durationDays)이 1일 미만이면 400으로 거절한다 (S6)")
    void create_400_whenDurationInvalid() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 0, "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("챌린지 기간(durationDays)이 100일을 넘으면 400으로 거절한다 (S6 · 0714 전체회의: 입력 한도 100일)")
    void create_400_whenDurationOverMax() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 101, "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("월급날 리셋을 켜고 월급날을 안 보내면 400으로 거절한다 (S6)")
    void create_400_whenPaydayMissing() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 100000, "startDate": "2026-12-01",
                                  "resetByPayday": true }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 진행 중인 챌린지가 있으면 409와 팀 공통 에러 본문을 돌려준다 (S6)")
    void create_409() throws Exception {
        when(service.create(anyLong(), any())).thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS));

        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("일별 지출이 음수면 400으로 거절한다 (S7)")
    void upsertDay_400_whenNegative() throws Exception {
        mvc.perform(post("/api/challenges/1/days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "date": "2026-12-02", "spentAmount": -1 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("아직 안 끝난 챌린지의 결과를 요청하면 409를 돌려준다 (S5)")
    void result_409() throws Exception {
        when(service.getResult(anyLong(), anyLong())).thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED));

        mvc.perform(get("/api/challenges/1/result"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("진행 중 챌린지가 없으면 현황 조회는 404를 돌려준다")
    void current_404() throws Exception {
        when(service.getCurrent(anyLong())).thenThrow(new CustomException(ChallengeErrorCode.NO_ACTIVE_CHALLENGE));

        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("현황 응답의 JSON 필드명(challenge·progress·consumption·adjustment)이 명세 계약대로 고정돼 있다")
    void current_responseShape() throws Exception {
        var view = new CurrentChallengeResponse.ChallengeView(
                1L, 30, LocalDate.of(2026, 6, 23), LocalDate.of(2026, 7, 22), 100000, 3333,
                ChallengeStatus.IN_PROGRESS);
        var progress = new CurrentChallengeResponse.Progress(5, 25, 4, 1, 2, 4200);
        var consumption = new CurrentChallengeResponse.Consumption(
                13000, 12000, 25000, 0.52, ConsumptionCharacter.NORMAL, AlertLevel.CAUTION);
        var adjustment = new CurrentChallengeResponse.Adjustment(0, 2);
        when(service.getCurrent(anyLong())).thenReturn(
                CurrentChallengeResponse.forChallenge(view, progress, consumption, List.of(), adjustment));

        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challenge.id").value(1))
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(3333))
                .andExpect(jsonPath("$.data.progress.elapsedDays").value(5))
                .andExpect(jsonPath("$.data.progress.remainingDays").value(25))
                .andExpect(jsonPath("$.data.progress.currentStreak").value(2))
                .andExpect(jsonPath("$.data.progress.savedAmountSoFar").value(4200))
                .andExpect(jsonPath("$.data.consumption.character").value("NORMAL"))
                .andExpect(jsonPath("$.data.consumption.alertLevel").value("CAUTION"))
                .andExpect(jsonPath("$.data.adjustment.maxCount").value(2))
                // 휴식 전용 블록은 챌린지 모드 응답에 아예 안 실려야 한다 — 기존 계약이 필드 추가로 안 흔들렸는지 고정
                .andExpect(jsonPath("$.data.rest").doesNotExist())
                .andExpect(jsonPath("$.data.keptRecords").doesNotExist());
    }

    @Test
    @DisplayName("휴식 중에 홈 현황(진행 중 챌린지 조회)을 부르면 챌린지 필드는 키가 빠지는 게 아니라 null 값으로 실려 내려가고, 휴식 정보와 보관 중인 내 기록(직전 종료 챌린지의 총 절약액과 최고 연속 성공일)만 함께 실린다 — 진행 중일 때 내려가던 나머지 응답 필드(progress·consumption·warningCards·adjustment)는 생략된다")
    void current_restModeShape() throws Exception {
        when(service.getCurrent(anyLong())).thenReturn(CurrentChallengeResponse.forRest(
                new CurrentChallengeResponse.RestView(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13)),
                new CurrentChallengeResponse.KeptRecords(68200, 14)));

        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                // 필드는 존재하되 값이 null — 안드가 이 null로 휴식 모드를 판별한다(휴식 명세의 응답 모양)
                .andExpect(jsonPath("$.data.challenge", nullValue()))
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.rest.restStartDate").value("2026-07-06"))
                .andExpect(jsonPath("$.data.rest.plannedResumeDate").value("2026-07-13"))
                .andExpect(jsonPath("$.data.keptRecords.savedAmount").value(68200))
                .andExpect(jsonPath("$.data.keptRecords.maxStreak").value(14))
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.consumption").doesNotExist())
                .andExpect(jsonPath("$.data.warningCards").doesNotExist())
                .andExpect(jsonPath("$.data.adjustment").doesNotExist());
    }

    @Test
    @DisplayName("지난 챌린지 리스트 응답의 JSON 필드명(items 카드의 8개 필드)이 명세 계약대로 고정돼 있다")
    void history_responseShape() throws Exception {
        var item = new ChallengeHistoryResponse.Item(12L, ChallengeStatus.SUCCESS,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 14), 14, 280000, 211800, 68200);
        when(service.getHistory(anyLong())).thenReturn(new ChallengeHistoryResponse(List.of(item)));

        mvc.perform(get("/api/challenges/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].challengeId").value(12))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].startDate").value("2026-05-01"))
                .andExpect(jsonPath("$.data.items[0].endDate").value("2026-05-14"))
                .andExpect(jsonPath("$.data.items[0].durationDays").value(14))
                .andExpect(jsonPath("$.data.items[0].budgetTotal").value(280000))
                .andExpect(jsonPath("$.data.items[0].actualSpent").value(211800))
                .andExpect(jsonPath("$.data.items[0].savedAmount").value(68200));
    }

    @Test
    @DisplayName("종료된 챌린지가 없으면 히스토리는 200에 빈 items 배열을 돌려준다 — 기록 없음은 에러가 아니다")
    void history_emptyItems() throws Exception {
        when(service.getHistory(anyLong())).thenReturn(new ChallengeHistoryResponse(List.of()));

        mvc.perform(get("/api/challenges/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("중도 포기가 성공하면 200과 확정 결과(challengeId·status=FAIL)를 돌려준다 — 상태 전이라 201이 아니라 200")
    void giveUp_200() throws Exception {
        when(service.giveUp(anyLong(), anyLong())).thenReturn(new GiveUpResponse(1L, ChallengeStatus.FAIL));

        mvc.perform(post("/api/challenges/1/give-up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(1))
                .andExpect(jsonPath("$.data.status").value("FAIL"));
    }

    @Test
    @DisplayName("이미 종료된 챌린지를 포기하면 409와 팀 공통 에러 본문(CHALLENGE_NOT_IN_PROGRESS)을 돌려준다")
    void giveUp_409() throws Exception {
        when(service.giveUp(anyLong(), anyLong()))
                .thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS));

        mvc.perform(post("/api/challenges/1/give-up"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_IN_PROGRESS"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("집중 카테고리 수정이 성공하면 200과 교체가 끝난 뒤의 카테고리를 돌려준다 — 있던 챌린지가 바뀌는 것뿐이라 201이 아니라 200")
    void updateFocusCategories_200() throws Exception {
        when(service.updateFocusCategories(anyLong(), anyLong(), any()))
                .thenReturn(new FocusCategoriesResponse(1L, List.of("배달", "카페")));

        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "categories": ["배달", "카페"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(1))
                .andExpect(jsonPath("$.data.categories[0]").value("배달"))
                .andExpect(jsonPath("$.data.categories[1]").value("카페"));
    }

    @Test
    @DisplayName("집중 카테고리 수정 요청에 카테고리 목록이 통째로 빠져 있으면 400으로 거절한다 — 빈 목록을 보내 '전부 해제'를 뜻하는 것과 달리, 목록 자체가 없으면 '전부 해제'인지 '안 건드림'인지 구분할 수 없다")
    void updateFocusCategories_400_whenCategoriesMissing() throws Exception {
        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("집중 카테고리 목록 안에 이름이 비어 있거나 공백뿐인 항목이 섞여 있으면 400으로 거절한다")
    void updateFocusCategories_400_whenCategoryBlank() throws Exception {
        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "categories": ["배달", "  "] }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("집중 카테고리 이름 하나가 저장 한도인 50자를 넘으면 400으로 거절한다 — 저장까지 내려가면 DB가 거부해 500이 되므로, 요청을 받는 자리에서 미리 거른다")
    void updateFocusCategories_400_whenCategoryTooLong() throws Exception {
        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"categories\": [\"" + "가".repeat(51) + "\"] }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 종료된 챌린지의 집중 카테고리를 수정하면 409와 팀 공통 에러 본문(CHALLENGE_NOT_IN_PROGRESS)을 돌려준다")
    void updateFocusCategories_409() throws Exception {
        when(service.updateFocusCategories(anyLong(), anyLong(), any()))
                .thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS));

        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "categories": ["배달"] }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_IN_PROGRESS"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("결과 응답의 JSON 필드명(period·summary·categoryBreakdown·emotionBreakdown)이 명세 계약대로 고정돼 있다")
    void result_responseShape() throws Exception {
        var period = new ResultResponse.Period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 14), 14);
        var summary = new ResultResponse.Summary(14, 0, 68200, 0, 14, 280000, 211800);
        when(service.getResult(anyLong(), anyLong()))
                .thenReturn(new ResultResponse(1L, ChallengeStatus.SUCCESS, period, summary, List.of(), List.of()));

        mvc.perform(get("/api/challenges/1/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.period.durationDays").value(14))
                .andExpect(jsonPath("$.data.summary.savedAmount").value(68200))
                .andExpect(jsonPath("$.data.summary.actualSpent").value(211800))
                .andExpect(jsonPath("$.data.categoryBreakdown").isArray())
                .andExpect(jsonPath("$.data.emotionBreakdown").isArray());
    }
}
