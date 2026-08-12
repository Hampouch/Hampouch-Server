package Hampouch.server.domain.minichallenge.controller;

import Hampouch.server.domain.minichallenge.dto.CreateMiniChallengeResponse;
import Hampouch.server.domain.minichallenge.dto.DailyMiniChallengesResponse;
import Hampouch.server.domain.minichallenge.dto.MiniCheckResponse;
import Hampouch.server.domain.minichallenge.service.MiniChallengeService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.MiniChallengeErrorCode;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(상태코드·JSON 필드 계약·팀 공통 에러 매핑) 검증. 서비스는 목 — DB 불필요(#1과 동일 구성).
 * 인증은 매 테스트 전 컨텍스트에 직접 세팅한다 — .with(authentication(...)) 방식은 시큐리티 필터가
 * 옮겨 줘야 작동해서 필터를 꺼 둔(addFilters=false) 이 슬라이스에선 401이 난다.
 */
@WebMvcTest(MiniChallengeController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class MiniChallengeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MiniChallengeService service;

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
    @DisplayName("로그인 정보 없이 미니 생성을 요청하면 401과 인증 필요 에러 본문으로 거절되고 서비스까지 내려가지 않는다")
    void create_401_whenNoAuthentication() throws Exception {
        TestSecurityContextHolder.clearContext(); // 공통 준비가 넣어 둔 로그인 상태를 이 테스트만 되돌린다
        mvc.perform(post("/api/mini-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "custom": { "title": "오늘 커피 사먹지 않기", "durationDays": 7 } }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("미니 생성 요청이 정상이면 201 Created와 Location 헤더, 생성 결과 본문을 돌려준다")
    void create_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new CreateMiniChallengeResponse(
                5L, "오늘 커피 사먹지 않기", 7, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12)));

        mvc.perform(post("/api/mini-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "custom": { "title": "오늘 커피 사먹지 않기", "durationDays": 7 } }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/mini-challenges/5"))
                // jsonPath("$.x.y") = 응답 JSON에서 값을 집는 주소 — $=문서 뿌리, .x=그 안의 x 필드, [0]=배열 첫 원소
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.miniChallengeId").value(5))
                .andExpect(jsonPath("$.data.durationDays").value(7))
                .andExpect(jsonPath("$.data.startDate").value("2026-07-06"))
                .andExpect(jsonPath("$.data.endDate").value("2026-07-12"));
    }

    @Test
    @DisplayName("본문 형태 위반(둘 다 보냄 등)이면 400과 팀 공통 에러 본문({code: MINI_INVALID_BODY, message, status})을 돌려준다 — recommendedId/custom은 둘 중 하나만 허용")
    void create_400_invalidBody() throws Exception {
        when(service.create(anyLong(), any()))
                .thenThrow(new CustomException(MiniChallengeErrorCode.MINI_INVALID_BODY));

        mvc.perform(post("/api/mini-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "recommendedId": 7, "custom": { "title": "둘 다", "durationDays": 7 } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MINI_INVALID_BODY"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("기간이 허용 목록(1·3·7·14·31일) 밖이면 400(MINI_INVALID_DURATION)을 돌려준다")
    void create_400_invalidDuration() throws Exception {
        when(service.create(anyLong(), any()))
                .thenThrow(new CustomException(MiniChallengeErrorCode.MINI_INVALID_DURATION));

        mvc.perform(post("/api/mini-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "custom": { "title": "5일 도전", "durationDays": 5 } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MINI_INVALID_DURATION"));
    }

    @Test
    @DisplayName("그날 조회 응답 JSON의 키 이름이 안드가 파싱하는 이름(date·summary·items…) 그대로 나간다 — record 필드명 변경은 컴파일이 못 잡으므로 이 테스트가 잡는다")
    void daily_responseShape() throws Exception {
        when(service.getDaily(anyLong(), any())).thenReturn(new DailyMiniChallengesResponse(
                LocalDate.of(2026, 7, 6),
                new DailyMiniChallengesResponse.Summary(3, 4, 3),
                List.of(new DailyMiniChallengesResponse.Item(
                        5L, "오늘 커피 사먹지 않기", 7, 4, 3, true))));

        // 키 이름을 문자열로 박아 검증 — record 필드명(=JSON 키)이 바뀌면 서버 코드는 다 초록인데 여기만 빨간불이 난다
        mvc.perform(get("/api/mini-challenges").param("date", "2026-07-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.date").value("2026-07-06"))
                .andExpect(jsonPath("$.data.summary.checkedCount").value(3))
                .andExpect(jsonPath("$.data.summary.totalCount").value(4))
                .andExpect(jsonPath("$.data.summary.streakDays").value(3))
                .andExpect(jsonPath("$.data.items[0].miniChallengeId").value(5))
                .andExpect(jsonPath("$.data.items[0].title").value("오늘 커피 사먹지 않기"))
                .andExpect(jsonPath("$.data.items[0].durationDays").value(7))
                .andExpect(jsonPath("$.data.items[0].progressDays").value(4))
                .andExpect(jsonPath("$.data.items[0].itemStreak").value(3))
                .andExpect(jsonPath("$.data.items[0].checked").value(true));
    }

    @Test
    @DisplayName("date 파라미터 형식이 잘못되면 400(VALIDATION_ERROR)과 어긋난 필드 이름을 돌려주고 서비스까지 내려가지 않는다")
    void daily_400_badDateFormat() throws Exception {
        mvc.perform(get("/api/mini-challenges").param("date", "07/06/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.date").exists());
        verify(service, never()).getDaily(anyLong(), any());
    }

    @Test
    @DisplayName("date 파라미터를 아예 안 보내면 서비스에 null이 넘어간다 — 오늘로 채우는 건 서비스의 Clock 몫")
    void daily_passesNullWhenDateOmitted() throws Exception {
        when(service.getDaily(anyLong(), any())).thenReturn(new DailyMiniChallengesResponse(
                LocalDate.of(2026, 7, 6),
                new DailyMiniChallengesResponse.Summary(0, 0, 0),
                List.of()));

        mvc.perform(get("/api/mini-challenges"))
                .andExpect(status().isOk());
        verify(service).getDaily(1L, null);
    }

    @Test
    @DisplayName("삭제가 성공하면 204 No Content에 바디 없이 돌려준다 — 204는 표준상 바디 금지라 팀 공통 {code,message,data} 봉투도 예외적으로 안 씌운다")
    void delete_204_noBody() throws Exception {
        doNothing().when(service).delete(anyLong(), anyLong());

        mvc.perform(delete("/api/mini-challenges/5"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("없는 미니를 삭제하면 404와 팀 공통 에러 본문({code: MINI_NOT_FOUND, message, status})을 돌려준다")
    void delete_404() throws Exception {
        doThrow(new CustomException(MiniChallengeErrorCode.MINI_NOT_FOUND))
                .when(service).delete(anyLong(), anyLong());

        mvc.perform(delete("/api/mini-challenges/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINI_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 미니면 403(MINI_FORBIDDEN)과 팀 공통 에러 본문을 돌려준다")
    void delete_403_forbidden() throws Exception {
        doThrow(new CustomException(MiniChallengeErrorCode.MINI_FORBIDDEN))
                .when(service).delete(anyLong(), anyLong());

        mvc.perform(delete("/api/mini-challenges/10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("체크 요청이 정상이면 200과 체크 결과(봉투 안 data.checked = true/false)를 돌려준다")
    void check_200() throws Exception {
        when(service.check(anyLong(), anyLong(), any()))
                .thenReturn(new MiniCheckResponse(5L, LocalDate.of(2026, 7, 6), true));

        mvc.perform(put("/api/mini-challenges/5/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "date": "2026-07-06", "checked": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.miniChallengeId").value(5))
                .andExpect(jsonPath("$.data.date").value("2026-07-06"))
                .andExpect(jsonPath("$.data.checked").value(true));
    }

    @Test
    @DisplayName("체크 요청에 필수 필드 checked가 없으면 400으로 거절한다")
    void check_400_whenCheckedMissing() throws Exception {
        mvc.perform(put("/api/mini-challenges/5/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "date": "2026-07-06" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("체크 바디의 date 형식이 잘못되면 400(VALIDATION_ERROR)으로 거절하고 서비스까지 내려가지 않는다 — 제로패딩 없는 2026-7-6은 ISO 형식이 아니다")
    void check_400_badDateFormat() throws Exception {
        mvc.perform(put("/api/mini-challenges/5/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "date": "2026-7-6", "checked": true }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400));
        verify(service, never()).check(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("미래 날짜 체크는 400(MINI_FUTURE_CHECK)을 돌려준다")
    void check_400_future() throws Exception {
        when(service.check(anyLong(), anyLong(), any()))
                .thenThrow(new CustomException(MiniChallengeErrorCode.MINI_FUTURE_CHECK));

        mvc.perform(put("/api/mini-challenges/5/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "date": "2027-01-01", "checked": true }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MINI_FUTURE_CHECK"));
    }
}
