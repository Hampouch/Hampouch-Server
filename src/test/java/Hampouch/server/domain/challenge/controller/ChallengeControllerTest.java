package Hampouch.server.domain.challenge.controller;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.service.EmotionSpending;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChallengeController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChallengeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChallengeService service;

    @MockitoBean
    JwtProvider jwtProvider;

    // 필터를 끈 슬라이스에서는 요청 인증이 전달되지 않아 보안 컨텍스트에 직접 설정한다.
    @BeforeEach
    void loginAsUser1() {
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void clearLogin() {
        TestSecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 정보 없이 챌린지 생성을 요청하면 401과 인증 필요 에러 본문으로 거절된다 — 요청 본문 검증보다 유저 식별이 먼저라, 본문이 틀려도 400이 아니라 401이 나간다")
    void create_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext();
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
    @DisplayName("목표 금액이 0원이어도 챌린지를 생성할 수 있다")
    void create_201_whenBudgetZero() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new CreateChallengeResponse(
                1L, 0, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 30), ChallengeStatus.IN_PROGRESS));

        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 0, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dailyLimit").value(0));
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
    @DisplayName("목표 금액이 음수면 400으로 거절한다")
    void create_400_whenBudgetNegative() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": -1, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("목표 금액이 상한 10,000,000원을 넘으면 400으로 거절한다")
    void create_400_whenBudgetOverMax() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 10000001, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("기간과 고정일을 모두 보내지 않으면 400을 반환한다")
    void create_400_whenPeriodAndFixedDayAreBothMissing() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": 100000, "startDate": "2026-12-01" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("고정일을 지정한 날짜 고정 챌린지 요청은 201을 반환한다")
    void createFixedDate_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new CreateChallengeResponse(
                1L, 4166, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 24),
                ChallengeStatus.IN_PROGRESS));

        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": 100000, "startDate": "2026-12-01",
                                  "fixedDay": 25 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.endDate").value("2026-12-24"));
    }

    @Test
    @DisplayName("기간과 고정일을 함께 보내면 400을 반환한다")
    void create_400_whenPeriodAndFixedDateAreBothSelected() throws Exception {
        mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationDays": 30, "budgetTotal": 100000, "startDate": "2026-12-01",
                                  "fixedDay": 25 }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("고정 날짜 도래 화면에 필요한 고정일·기간·목표를 반환한다")
    void nextFixedDateChallenge_responseShape() throws Exception {
        when(service.getNextFixedDateChallenge(anyLong())).thenReturn(new NextFixedDateChallengeResponse(
                10L,
                1,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                30,
                350000,
                11666));

        mvc.perform(get("/api/challenges/fixed-date/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceChallengeId").value(10))
                .andExpect(jsonPath("$.data.fixedDay").value(1))
                .andExpect(jsonPath("$.data.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-06-30"))
                .andExpect(jsonPath("$.data.durationDays").value(30))
                .andExpect(jsonPath("$.data.budgetTotal").value(350000))
                .andExpect(jsonPath("$.data.dailyLimit").value(11666));
    }

    @Test
    @DisplayName("유효한 날짜 고정 챌린지 시작 요청은 생성 결과를 반환한다")
    void startFixedDate_200() throws Exception {
        when(service.startFixedDate(anyLong(), any())).thenReturn(new CreateChallengeResponse(
                11L, 11666, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 30),
                ChallengeStatus.IN_PROGRESS));

        mvc.perform(post("/api/challenges/fixed-date/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sourceChallengeId": 10, "startDate": "2026-12-01",
                                  "budgetTotal": 350000, "fixedDay": 1 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challengeId").value(11))
                .andExpect(jsonPath("$.data.dailyLimit").value(11666));
    }

    @Test
    @DisplayName("날짜 고정 챌린지 시작 요청의 직전 챌린지 ID가 양수가 아니면 400을 반환한다")
    void startFixedDate_400_whenSourceIdIsNotPositive() throws Exception {
        mvc.perform(post("/api/challenges/fixed-date/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sourceChallengeId": 0, "startDate": "2026-12-01",
                                  "budgetTotal": 350000, "fixedDay": 1 }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).startFixedDate(anyLong(), any());
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
    @DisplayName("date를 생략하고 오늘 챌린지가 없으면 challenge null을 반환한다")
    void current_noChallenge() throws Exception {
        when(service.getCurrent(anyLong())).thenReturn(CurrentChallengeResponse.empty());

        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.challenge", nullValue()));
    }

    @Test
    @DisplayName("선택 날짜에 챌린지가 없으면 challenge null만 담은 200을 돌려준다")
    void current_noChallengeOnSelectedDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 12);
        when(service.getCurrent(1L, date)).thenReturn(CurrentChallengeResponse.empty());

        mvc.perform(get("/api/challenges/current").param("date", "2026-04-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("challenge")))
                .andExpect(jsonPath("$.data.challenge", nullValue()))
                .andExpect(jsonPath("$.data.progress").doesNotExist());

        verify(service).getCurrent(1L, date);
        verify(service, never()).getCurrent(1L);
    }

    @Test
    @DisplayName("date가 ISO 날짜 형식이 아니면 서비스 호출 전 400으로 거절한다")
    void current_400_whenDateFormatInvalid() throws Exception {
        mvc.perform(get("/api/challenges/current").param("date", "04/12/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.date").exists());

        verify(service, never()).getCurrent(anyLong());
        verify(service, never()).getCurrent(anyLong(), any(LocalDate.class));
    }

    @Test
    @DisplayName("현황 응답의 JSON 필드명(challenge·progress·consumption·warningCards·expenseInputState·adjustment)이 명세 계약대로 고정돼 있다")
    void current_responseShape() throws Exception {
        var view = new CurrentChallengeResponse.ChallengeView(
                1L, 30, LocalDate.of(2026, 6, 23), LocalDate.of(2026, 7, 22), 100000, 3333,
                ChallengeStatus.IN_PROGRESS);
        var progress = new CurrentChallengeResponse.Progress(5, 25, 4, 1, 2, 4200);
        var consumption = new CurrentChallengeResponse.Consumption(
                13000, 12000, 25000, 0.52, ConsumptionCharacter.NORMAL, AlertLevel.CAUTION);
        var adjustment = new CurrentChallengeResponse.Adjustment(0, 2);
        when(service.getCurrent(anyLong())).thenReturn(
                CurrentChallengeResponse.forChallenge(
                        view, progress, consumption, List.of(), ExpenseInputState.NORMAL, adjustment));

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
                .andExpect(jsonPath("$.data.warningCards").isEmpty())
                .andExpect(jsonPath("$.data.expenseInputState").value("NORMAL"))
                .andExpect(jsonPath("$.data.adjustment.maxCount").value(2))
                .andExpect(jsonPath("$.data.rest").doesNotExist())
                .andExpect(jsonPath("$.data.keptRecords").doesNotExist());
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
    @DisplayName("목표 금액 조정이 성공하면 200과 새 목표·하루 한도·사용 횟수·상한을 돌려준다 — 상태 전이라 201이 아니라 200")
    void adjust_200() throws Exception {
        when(service.adjustGoal(anyLong(), anyLong(), any()))
                .thenReturn(new AdjustGoalResponse(1L, 308000, 22000, 1, 2));

        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_10" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.budgetTotal").value(308000))
                .andExpect(jsonPath("$.data.dailyLimit").value(22000))
                .andExpect(jsonPath("$.data.usedCount").value(1))
                .andExpect(jsonPath("$.data.maxCount").value(2));
    }

    @Test
    @DisplayName("+30% 조정 옵션을 보내면 200과 새 목표·하루 한도를 돌려준다")
    void adjust_200_whenPlusThirtyOption() throws Exception {
        when(service.adjustGoal(anyLong(), anyLong(), any()))
                .thenReturn(new AdjustGoalResponse(1L, 364000, 26000, 1, 2));

        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_30" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetTotal").value(364000))
                .andExpect(jsonPath("$.data.dailyLimit").value(26000));
    }

    @Test
    @DisplayName("직접 입력 금액만 보내도 200으로 처리된다 — 화면의 직접 입력 칸에 대응")
    void adjust_200_whenDirectAmount() throws Exception {
        when(service.adjustGoal(anyLong(), anyLong(), any()))
                .thenReturn(new AdjustGoalResponse(1L, 350000, 25000, 1, 2));

        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": 350000 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetTotal").value(350000))
                .andExpect(jsonPath("$.data.dailyLimit").value(25000));
    }

    @Test
    @DisplayName("조정 옵션이 정해진 세 값(PLUS_10·PLUS_20·PLUS_30) 밖이면 400으로 거절한다")
    void adjust_400_whenOptionUnknown() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_50" }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).adjustGoal(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("옵션도 직접 입력 금액도 없으면 400으로 거절한다 — 무엇으로 조정할지 알 수 없다")
    void adjust_400_whenNeitherChoiceGiven() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest());
        verify(service, never()).adjustGoal(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("옵션과 직접 입력 금액을 함께 보내면 400으로 거절한다 — 어느 쪽이 이기는지가 계약에 없다")
    void adjust_400_whenBothChoicesGiven() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_10", "budgetTotal": 350000 }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).adjustGoal(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("직접 입력 목표를 0원으로 조정할 수 있다")
    void adjust_200_whenDirectAmountZero() throws Exception {
        when(service.adjustGoal(anyLong(), anyLong(), any()))
                .thenReturn(new AdjustGoalResponse(1L, 0, 0, 1, 2));

        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": 0 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetTotal").value(0))
                .andExpect(jsonPath("$.data.dailyLimit").value(0));
    }

    @Test
    @DisplayName("직접 입력 목표가 음수면 400으로 거절한다")
    void adjust_400_whenDirectAmountNegative() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": -1 }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).adjustGoal(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("직접 입력 금액이 상한 10,000,000원을 넘으면 400으로 거절한다 — 생성 요청과 같은 상한이다")
    void adjust_400_whenDirectAmountOverMax() throws Exception {
        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "budgetTotal": 10000001 }
                                """))
                .andExpect(status().isBadRequest());
        verify(service, never()).adjustGoal(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("조정 가능 횟수를 다 썼으면 409와 팀 공통 에러 본문(ADJUSTMENT_LIMIT_EXCEEDED)을 돌려준다")
    void adjust_409_whenCountExhausted() throws Exception {
        when(service.adjustGoal(anyLong(), anyLong(), any()))
                .thenThrow(new CustomException(ChallengeErrorCode.ADJUSTMENT_LIMIT_EXCEEDED));

        mvc.perform(post("/api/challenges/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "option": "PLUS_20" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("집중 카테고리 수정 엔드포인트가 제거돼 PUT /{id}/focus-categories는 404를 반환한다 (#194)")
    void focusCategoriesRoute_404() throws Exception {
        mvc.perform(put("/api/challenges/1/focus-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "categories": ["배달"] }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("직전 종료 챌린지가 있으면 추천 조회가 message만 돌려준다")
    void recommendation_200() throws Exception {
        when(service.getRecommendation(anyLong())).thenReturn(new RecommendationResponse(
                "목표보다 60,000원 절약했어요! 이번엔 조금 더 타이트하게 가볼까요? "
                        + "기간은 그대로 30일, 목표는 360,000원으로 줄여서 새 기록에 도전해봐요."));

        mvc.perform(get("/api/challenges/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data", aMapWithSize(1)))
                .andExpect(jsonPath("$.data.message").value(
                        "목표보다 60,000원 절약했어요! 이번엔 조금 더 타이트하게 가볼까요? "
                                + "기간은 그대로 30일, 목표는 360,000원으로 줄여서 새 기록에 도전해봐요."));
    }

    @Test
    @DisplayName("종료된 챌린지가 없으면 추천 조회가 404와 팀 공통 에러 본문(NO_ENDED_CHALLENGE)을 돌려준다")
    void recommendation_404_whenNoEndedChallenge() throws Exception {
        when(service.getRecommendation(anyLong()))
                .thenThrow(new CustomException(ChallengeErrorCode.NO_ENDED_CHALLENGE));

        mvc.perform(get("/api/challenges/recommendation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ENDED_CHALLENGE"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("결과 응답의 JSON 필드명(period·summary·emotionBreakdown)이 명세 계약대로 고정돼 있다(categoryBreakdown 삭제) — emotionBreakdown 원소의 필드값까지 확인한다")
    void result_responseShape() throws Exception {
        var period = new ResultResponse.Period(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 14),
                14);
        var summary = new ResultResponse.Summary(
                14, 0, 68200, 0, 14, 280000, 211800);
        List<EmotionSpending> emotionBreakdown = List.of(
                new EmotionSpending(ExpenseEmotion.STRESS, 8_000, 80));

        when(service.getResult(anyLong(), anyLong()))
                .thenReturn(new ResultResponse(
                        1L,
                        ChallengeStatus.SUCCESS,
                        null,
                        period,
                        summary,
                        emotionBreakdown));

        mvc.perform(get("/api/challenges/1/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.period.durationDays").value(14))
                .andExpect(jsonPath("$.data.summary.savedAmount").value(68200))
                .andExpect(jsonPath("$.data.summary.actualSpent").value(211800))
                .andExpect(jsonPath("$.data.categoryBreakdown").doesNotExist())
                .andExpect(jsonPath("$.data.emotionBreakdown", hasSize(1)))
                .andExpect(jsonPath("$.data.emotionBreakdown[0].emotion").value("STRESS"))
                .andExpect(jsonPath("$.data.emotionBreakdown[0].amount").value(8_000))
                .andExpect(jsonPath("$.data.emotionBreakdown[0].ratio").value(80));
    }
    @Test
    @DisplayName("아직 최종 종료하지 않은 챌린지의 결과 응답은 expenseLockedAt 필드가 null로 나간다 — 클라가 이 값으로 종료 팝업을 띄울지 정한다")
    void result_expenseLockedAtNullWhenExpenseNotLocked() throws Exception {
        var period = new ResultResponse.Period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 14), 14);
        var summary = new ResultResponse.Summary(14, 0, 68200, 0, 14, 280000, 211800);
        when(service.getResult(anyLong(), anyLong()))
                .thenReturn(new ResultResponse(1L, ChallengeStatus.SUCCESS, null, period, summary, List.of()));

        mvc.perform(get("/api/challenges/1/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseLockedAt").value(nullValue()));
    }

    @Test
    @DisplayName("최종 종료 요청이 정상이면 200과 확정된 성패·종료 시각을 돌려준다 — 새 리소스가 생기는 게 아니라 상태가 바뀌는 것이라 201이 아니다")
    void close_200() throws Exception {
        when(service.close(anyLong(), anyLong())).thenReturn(
                new CloseResponse(1L, ChallengeStatus.SUCCESS, LocalDateTime.of(2026, 5, 20, 9, 30)));

        mvc.perform(post("/api/challenges/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeId").value(1))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expenseLockedAt").value("2026-05-20T09:30:00"));
    }

    @Test
    @DisplayName("기간이 안 끝난 챌린지의 최종 종료 요청은 409와 팀 공통 에러 본문(CHALLENGE_NOT_ENDED)을 돌려준다")
    void close_409_whenNotEnded() throws Exception {
        when(service.close(anyLong(), anyLong()))
                .thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED));

        mvc.perform(post("/api/challenges/1/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_ENDED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("이미 최종 종료한 챌린지의 종료 요청은 409와 팀 공통 에러 본문(CHALLENGE_ALREADY_CLOSED)을 돌려준다")
    void close_409_whenAlreadyClosed() throws Exception {
        when(service.close(anyLong(), anyLong()))
                .thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_CLOSED));

        mvc.perform(post("/api/challenges/1/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_CLOSED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("로그인 정보 없이 최종 종료를 요청하면 401로 거절되고 서비스는 호출되지 않는다")
    void close_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext();

        mvc.perform(post("/api/challenges/1/close"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
        verify(service, never()).close(anyLong(), anyLong());
    }
}
