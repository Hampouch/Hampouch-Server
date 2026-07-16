package Hampouch.server.domain.minichallenge.controller;

import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse;
import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse.Item;
import Hampouch.server.domain.minichallenge.service.RecommendedMiniChallengeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 계층(파라미터 바인딩·응답 형태) 검증. 서비스는 목 — DB 불필요.
 */
@WebMvcTest(RecommendedMiniChallengeController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class RecommendedMiniChallengeControllerTest {

    private static final String PATH = "/api/mini-challenges/recommended";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RecommendedMiniChallengeService service;

    @Test
    @DisplayName("durationDays 쿼리 파라미터 없이 조회하면 서비스에 전체 조회(널)로 넘어가고, 응답은 200과 공통 응답 틀(code, data)로 내려가며 data.items의 모든 항목이 recommendedId·title·durationDays를 담는다")
    void recommended_all() throws Exception {
        when(service.getRecommended(null)).thenReturn(new RecommendedMiniChallengeListResponse(List.of(
                new Item(7L, "편의점 디저트 안 먹기", 7),
                new Item(1L, "오늘 커피 사먹지 않기", 1))));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].recommendedId").value(7))
                .andExpect(jsonPath("$.data.items[0].title").value("편의점 디저트 안 먹기"))
                .andExpect(jsonPath("$.data.items[0].durationDays").value(7))
                // 둘째 항목도 세 필드를 전부 확인 — 첫 항목만 온전한 게 아니라 모든 항목이 같은 형태다
                .andExpect(jsonPath("$.data.items[1].recommendedId").value(1))
                .andExpect(jsonPath("$.data.items[1].title").value("오늘 커피 사먹지 않기"))
                .andExpect(jsonPath("$.data.items[1].durationDays").value(1));

        verify(service).getRecommended(null); // 파라미터 생략 = 전체(널)로 서비스에 전달
    }

    @Test
    @DisplayName("durationDays 쿼리 파라미터를 주면 컨트롤러가 값을 바꾸지 않고 그대로 서비스에 넘긴다 (필터링 자체는 서비스·리포지토리 몫)")
    void recommended_withDuration() throws Exception {
        when(service.getRecommended(7)).thenReturn(new RecommendedMiniChallengeListResponse(List.of(
                new Item(6L, "편의점 디저트 안 먹기", 7))));

        mvc.perform(get(PATH).param("durationDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].durationDays").value(7));

        verify(service).getRecommended(7);
    }

    @Test
    @DisplayName("추천이 하나도 없으면 items가 빈 배열인 200으로 내려간다 (에러 아님 — 허용 기간 목록(1·3·7·14·31) 밖 durationDays로 조회한 경우 포함)")
    void recommended_empty() throws Exception {
        when(service.getRecommended(5)).thenReturn(new RecommendedMiniChallengeListResponse(List.of()));

        mvc.perform(get(PATH).param("durationDays", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("durationDays에 숫자가 아닌 값이 오면 현재는 500이다 — 타입 바인딩 실패의 400 매핑은 공통 핸들러(팀) 몫이라 현 동작을 고정해 둔다")
    void recommended_nonNumericDuration_currently500() throws Exception {
        // durationDays=abc는 Integer 변환 실패(MethodArgumentTypeMismatchException)를 일으키는데,
        // 공통 GlobalExceptionHandler(나연 담당 — 이 브랜치 수정 금지)에 그 예외 전용 핸들러가 없어
        // Exception 포괄 핸들러로 500 INTERNAL_SERVER_ERROR가 내려간다. 이 테스트는 그 현재 동작을
        // 고정해 두는 회귀 문서 — 공통 핸들러에 400 매핑이 추가되면(팀 싱크 전달 예정) 400으로 갱신할 것.
        mvc.perform(get(PATH).param("durationDays", "abc"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        verify(service, never()).getRecommended(any()); // 바인딩 단계에서 실패 — 서비스까지 안 간다
    }
}
