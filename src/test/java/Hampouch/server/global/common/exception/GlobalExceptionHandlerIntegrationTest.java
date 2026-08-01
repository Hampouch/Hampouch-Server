package Hampouch.server.global.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 메서드 불일치·미매핑 경로 예외는 컨트롤러에 못 닿고 MVC 단계에서 나므로,
 * 슬라이스가 아니라 실제 매핑·시큐리티 필터를 다 태운 통합으로 검증한다.
 * 요청 경로는 전부 permitAll 목록에 있는 것만 쓴다 — 아니면 401이 먼저 나가 재현이 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("등록된 경로에 지원하지 않는 메서드로 요청하면 405 상태와 지원 메서드가 담긴 Allow 헤더로 응답한다")
    void unsupportedMethod_returns405WithAllowHeader() throws Exception {
        mvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("지원 메서드가 여러 개인 경로에서도 405 응답의 Allow 헤더에 그 메서드들이 전부 담긴다")
    void unsupportedMethod_allowHeaderListsEveryMethod() throws Exception {
        mvc.perform(patch("/api/mini-challenges"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("어느 매핑에도 없는 경로로 요청하면 404 상태와 리소스 없음 응답을 준다")
    void unmappedPath_returns404() throws Exception {
        mvc.perform(get("/api/challenges/zzz/zzz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.status").value(404));
    }
}
