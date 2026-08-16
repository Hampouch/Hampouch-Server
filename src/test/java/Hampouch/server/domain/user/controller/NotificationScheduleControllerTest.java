package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.entity.NotificationDay;
import Hampouch.server.domain.user.service.NotificationScheduleService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationScheduleController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationScheduleControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NotificationScheduleService notificationScheduleService;

    @MockitoBean
    JwtProvider jwtProvider; // JwtFilter가 Filter 타입이라 슬라이스에 자동 포함되며 요구하는 의존성

    @BeforeEach
    void setUpAuthentication() {
        var authentication = new UsernamePasswordAuthenticationToken(1L, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static final String REQUEST_BODY = """
            {
              "challengeAlert": true,
              "battleAlert": true,
              "communityAlert": true,
              "recordAlert": {
                "enabled": true,
                "missingInput": {
                  "enabled": true,
                  "days": ["MON", "TUE"],
                  "time": "21:00"
                },
                "limitExceeded": {
                  "enabled": true
                }
              }
            }
            """;

    private static NotificationScheduleResponse defaultResponse() {
        return new NotificationScheduleResponse(
                true, true, true,
                new NotificationScheduleResponse.RecordAlert(
                        true,
                        new NotificationScheduleResponse.MissingInput(
                                true, EnumSet.allOf(NotificationDay.class), LocalTime.of(21, 0)),
                        new NotificationScheduleResponse.LimitExceeded(true)
                )
        );
    }

    // ---------- 알림 설정 조회 ----------

    @Test
    @DisplayName("정상 조회면 200과 전체 알림 설정을 반환한다")
    void getSchedule_returns200WithFullSchedule() throws Exception {
        when(notificationScheduleService.getSchedule(1L)).thenReturn(defaultResponse());

        mvc.perform(get("/api/users/me/notification/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.challengeAlert").value(true))
                .andExpect(jsonPath("$.data.recordAlert.missingInput.time").value("21:00"))
                .andExpect(jsonPath("$.data.recordAlert.missingInput.days[0]").value("MON"));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void getSchedule_returns403WhenUserDeleted() throws Exception {
        when(notificationScheduleService.getSchedule(1L)).thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        mvc.perform(get("/api/users/me/notification/schedule"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }

    // ---------- 알림 설정 변경 ----------

    @Test
    @DisplayName("정상 변경이면 200과 바뀐 전체 설정을 반환한다")
    void updateSchedule_returns200WithReplacedSchedule() throws Exception {
        when(notificationScheduleService.updateSchedule(eq(1L), any())).thenReturn(defaultResponse());

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("요일이 비어 있으면 400을 반환하고 fieldErrors에 중첩 경로가 담긴다")
    void updateSchedule_returns400WhenDaysEmpty() throws Exception {
        String body = """
                {
                  "challengeAlert": true,
                  "battleAlert": true,
                  "communityAlert": true,
                  "recordAlert": {
                    "enabled": true,
                    "missingInput": { "enabled": true, "days": [], "time": "21:00" },
                    "limitExceeded": { "enabled": true }
                  }
                }
                """;

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['recordAlert.missingInput.days']").exists());
    }

    @Test
    @DisplayName("시각이 HH:mm 형식이 아니면 400을 반환하고 fieldErrors에 중첩 경로가 담긴다")
    void updateSchedule_returns400WhenTimeFormatInvalid() throws Exception {
        String body = """
                {
                  "challengeAlert": true,
                  "battleAlert": true,
                  "communityAlert": true,
                  "recordAlert": {
                    "enabled": true,
                    "missingInput": { "enabled": true, "days": ["MON"], "time": "25:99" },
                    "limitExceeded": { "enabled": true }
                  }
                }
                """;

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['recordAlert.missingInput.time']").exists());
    }

    @Test
    @DisplayName("recordAlert가 통째로 빠지면 400을 반환한다")
    void updateSchedule_returns400WhenRecordAlertMissing() throws Exception {
        String body = """
                {
                  "challengeAlert": true,
                  "battleAlert": true,
                  "communityAlert": true
                }
                """;

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.recordAlert").exists());
    }

    @Test
    @DisplayName("challengeAlert가 누락되면 기본값 false로 조용히 통과하지 않고 400을 반환한다 " +
            "- enabled류 필드가 primitive boolean이면 Jackson이 기본값을 채워 이 검증을 통과 못 시킨다")
    void updateSchedule_returns400WhenChallengeAlertMissing() throws Exception {
        String body = """
                {
                  "battleAlert": true,
                  "communityAlert": true,
                  "recordAlert": {
                    "enabled": true,
                    "missingInput": { "enabled": true, "days": ["MON"], "time": "21:00" },
                    "limitExceeded": { "enabled": true }
                  }
                }
                """;

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.challengeAlert").exists());
    }

    @Test
    @DisplayName("recordAlert.enabled가 누락되면 400을 반환한다")
    void updateSchedule_returns400WhenRecordAlertEnabledMissing() throws Exception {
        String body = """
                {
                  "challengeAlert": true,
                  "battleAlert": true,
                  "communityAlert": true,
                  "recordAlert": {
                    "missingInput": { "enabled": true, "days": ["MON"], "time": "21:00" },
                    "limitExceeded": { "enabled": true }
                  }
                }
                """;

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['recordAlert.enabled']").exists());
    }

    @Test
    @DisplayName("탈퇴한 회원이면 403을 반환한다")
    void updateSchedule_returns403WhenUserDeleted() throws Exception {
        when(notificationScheduleService.updateSchedule(eq(1L), any()))
                .thenThrow(new CustomException(UserErrorCode.USER_DELETED));

        mvc.perform(put("/api/users/me/notification/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));
    }
}
