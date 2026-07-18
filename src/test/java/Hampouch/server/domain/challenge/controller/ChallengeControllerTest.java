package Hampouch.server.domain.challenge.controller;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요.
 */
@WebMvcTest(ChallengeController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class ChallengeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChallengeService service;

    @MockitoBean
    JwtProvider jwtProvider; //임시 추가

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
                new CurrentChallengeResponse(view, progress, consumption, List.of(), adjustment));

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
                .andExpect(jsonPath("$.data.adjustment.maxCount").value(2));
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
