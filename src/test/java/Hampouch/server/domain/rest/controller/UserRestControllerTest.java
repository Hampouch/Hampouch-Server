package Hampouch.server.domain.rest.controller;

import Hampouch.server.domain.rest.dto.RestResumeResponse;
import Hampouch.server.domain.rest.dto.RestStartResponse;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.service.UserRestService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.RestErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 휴식 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요.
 * 인증은 매 테스트 전 컨텍스트에 직접 세팅한다 — .with(authentication(...)) 방식은 시큐리티 필터가
 * 옮겨 줘야 작동해서 필터를 꺼 둔(addFilters=false) 이 슬라이스에선 401이 난다. 도메인 전환 때 같은 함정 주의.
 */
@WebMvcTest(UserRestController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class UserRestControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserRestService service;

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
    @DisplayName("로그인 정보 없이 휴식 시작을 요청하면 401과 인증 필요 에러 본문으로 거절된다 — 요청 본문 검증보다 유저 식별이 먼저라, 본문이 틀려도 400이 아니라 401이 나간다")
    void start_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext(); // 공통 준비가 넣어 둔 로그인 상태를 이 테스트만 되돌린다
        // 일부러 검증에도 걸리는 본문(restDays 0) — 유저 식별이 본문 검증보다 먼저임을 응답 코드로 증명
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 0 }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
        verify(service, never()).start(anyLong(), any());
    }

    @Test
    @DisplayName("로그인 정보 없이 복귀를 요청해도 똑같이 401로 거절된다")
    void resume_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext();
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "NOW" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
        verify(service, never()).resume(anyLong(), any());
    }

    @Test
    @DisplayName("휴식 시작 요청이 정상이면 201 Created와 Location 헤더, 시작·복귀 예정일 본문을 돌려준다")
    void start_201() throws Exception {
        when(service.start(anyLong(), any())).thenReturn(new RestStartResponse(
                3L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13)));

        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 7 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rests/3"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.restId").value(3))
                .andExpect(jsonPath("$.data.restStartDate").value("2026-07-06"))
                .andExpect(jsonPath("$.data.plannedResumeDate").value("2026-07-13"));
    }

    @Test
    @DisplayName("쉬는 일수가 1 미만이면 400으로 거절되고 서비스까지 내려가지 않는다 — 응답 code는 VALIDATION_ERROR다")
    void start_400_whenRestDaysBelowOne() throws Exception {
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).start(anyLong(), any());
    }

    @Test
    @DisplayName("쉬는 일수의 경계값(1일과 3650일)은 검증을 통과해 정상 처리된다 — 상한과 하한 어느 쪽도 한 칸 어긋나게 조여지지 않았음을 확인한다")
    void start_acceptsBoundaryRestDays() throws Exception {
        // 허용 범위 [1, 3650]의 양끝 값을 그대로 보내 둘 다 통과함을 본다. 하한을 Min(2)나 >1로,
        // 상한을 Max(3649)나 <3650으로 잘못 조이면(off-by-one) 이 값이 400으로 튕겨 여기서 깨진다.
        // 바깥 한 칸(0·3651)을 거절하는 짝 테스트가 위아래에 있어, 넷을 합치면 경계선이 정확히 [1,3650]임이 증명된다.
        when(service.start(anyLong(), any())).thenReturn(new RestStartResponse(
                3L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13)));

        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 1 }
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 3650 }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("쉬는 일수가 서버 방어 상한(3650일)을 넘으면 400으로 거절한다 — 상한 없이 극단적으로 큰 값을 받으면 복귀 예정일이 데이터베이스 날짜 범위를 넘어 500이 날 수 있어 미리 막는다")
    void start_400_whenRestDaysOverMax() throws Exception {
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 3651 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).start(anyLong(), any());
    }

    @Test
    @DisplayName("쉬는 일수를 안 보내면 400으로 거절한다")
    void start_400_whenRestDaysMissing() throws Exception {
        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("이미 휴식 중이면 409와 팀 공통 에러 본문을 돌려준다")
    void start_409_whenAlreadyResting() throws Exception {
        when(service.start(anyLong(), any()))
                .thenThrow(new CustomException(RestErrorCode.REST_ALREADY_ACTIVE));

        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 7 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REST_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("진행 중 챌린지가 있으면 휴식 시작이 409로 거절되고 에러 코드로 원인이 구분된다")
    void start_409_whenChallengeActive() throws Exception {
        when(service.start(anyLong(), any()))
                .thenThrow(new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS));

        mvc.perform(post("/api/rests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "restDays": 7 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("지금 바로 복귀 요청이 정상이면 200과 실제 복귀일을 돌려준다 — 이 응답 본문에는 복귀 예정일 필드가 실리지 않는다")
    void resume_200_now() throws Exception {
        UserRest rest = UserRest.start(1L, LocalDate.of(2026, 7, 6), 7);
        ReflectionTestUtils.setField(rest, "id", 3L);
        when(service.resume(anyLong(), any()))
                .thenReturn(RestResumeResponse.resumed(rest, LocalDate.of(2026, 7, 10)));

        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "NOW" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.restId").value(3))
                .andExpect(jsonPath("$.data.resumeDate").value("2026-07-10"))
                .andExpect(jsonPath("$.data.plannedResumeDate").doesNotExist());
    }

    @Test
    @DisplayName("조금 더 쉬기 요청이 정상이면 200과 미뤄진 복귀 예정일을 돌려준다 — 이 응답 본문에는 실제 복귀일 필드가 실리지 않는다")
    void resume_200_extend() throws Exception {
        UserRest rest = UserRest.start(1L, LocalDate.of(2026, 7, 6), 7);
        ReflectionTestUtils.setField(rest, "id", 3L);
        rest.extend(LocalDate.of(2026, 7, 10), 3); // 예정일 7/13 → 7/16 (기준일은 예정일이 아직 안 지나 예정일 그대로)
        when(service.resume(anyLong(), any())).thenReturn(RestResumeResponse.extended(rest));

        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND", "extendDays": 3 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restId").value(3))
                .andExpect(jsonPath("$.data.plannedResumeDate").value("2026-07-16"))
                .andExpect(jsonPath("$.data.resumeDate").doesNotExist());
    }

    @Test
    @DisplayName("복귀 방식 선택 값(when)에 선택지에 없는 값이 오면 400으로 거절한다 — 이렇게 JSON을 객체로 바꾸다 실패하는 경우도 응답 code는 다른 400들과 똑같이 VALIDATION_ERROR로 나온다")
    void resume_400_whenUnknownWhen() throws Exception {
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "SOON" }
                                """))
                .andExpect(status().isBadRequest())
                // "SOON"은 enum(NOW/TOMORROW/EXTEND) 값으로 만들 수가 없어 JSON→객체 변환(역직렬화) 단계에서
                // 실패한다 — 객체가 없으니 @Valid는 실행조차 안 되고, @Min 등을 잡는 핸들러가 아니라
                // HttpMessageNotReadableException 핸들러를 탄다(restDays:3651이 파싱 후 @Max에 걸리는 것과 다른 경로).
                // 두 핸들러가 다른 예외를 잡아도 안드가 보는 code는 같은 VALIDATION_ERROR로 통일돼야 한다.
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).resume(anyLong(), any());
    }

    @Test
    @DisplayName("조금 더 쉬기가 아닌 선택지에 연장 일수가 딸려 오면 거절하지 않고 무시한 채 정상 처리한다")
    void resume_ignoresExtendDaysWhenNotExtend() throws Exception {
        UserRest rest = UserRest.start(1L, LocalDate.of(2026, 7, 6), 7);
        ReflectionTestUtils.setField(rest, "id", 3L);
        when(service.resume(anyLong(), any()))
                .thenReturn(RestResumeResponse.resumed(rest, LocalDate.of(2026, 7, 10)));

        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "NOW", "extendDays": 3 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeDate").value("2026-07-10"));
    }

    @Test
    @DisplayName("복귀 방식 선택 값(when)을 안 보내면 400으로 거절한다")
    void resume_400_whenMissing() throws Exception {
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("조금 더 쉬기인데 연장 일수를 안 보내면 400으로 거절한다")
    void resume_400_whenExtendDaysMissing() throws Exception {
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).resume(anyLong(), any());
    }

    @Test
    @DisplayName("연장 일수가 1 미만이면 400으로 거절한다")
    void resume_400_whenExtendDaysBelowOne() throws Exception {
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND", "extendDays": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("연장 일수가 서버 방어 상한(3650일)을 넘으면 400으로 거절한다")
    void resume_400_whenExtendDaysOverMax() throws Exception {
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND", "extendDays": 3651 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("연장 일수의 경계값(1일과 3650일)은 검증을 통과해 정상 처리된다")
    void resume_acceptsBoundaryExtendDays() throws Exception {
        UserRest rest = UserRest.start(1L, LocalDate.of(2026, 7, 6), 7);
        ReflectionTestUtils.setField(rest, "id", 3L);
        when(service.resume(anyLong(), any())).thenReturn(RestResumeResponse.extended(rest));

        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND", "extendDays": 1 }
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "EXTEND", "extendDays": 3650 }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("휴식 중이 아닌데 복귀를 요청하면 404와 팀 공통 에러 본문을 돌려준다")
    void resume_404_whenNotResting() throws Exception {
        when(service.resume(anyLong(), any()))
                .thenThrow(new CustomException(RestErrorCode.REST_NOT_ACTIVE));

        mvc.perform(post("/api/rests/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "when": "NOW" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REST_NOT_ACTIVE"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
