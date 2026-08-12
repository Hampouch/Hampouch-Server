package Hampouch.server.global.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 다른 컨트롤러 테스트와 달리 시큐리티 필터를 켠 채로 돈다 — 문서 경로가 인증 없이 열리는지가
 * 검증 대상이라 addFilters = false로 끄면 SecurityConfig의 permitAll이 검증되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("Authorization 헤더 없이 /v3/api-docs를 열면 200과 함께 컨트롤러에서 수집한 경로가 담긴 문서가 내려온다")
    void apiDocsAreServedWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists());
    }

    @Test
    @DisplayName("Authorization 헤더 없이 Swagger UI 경로를 열면 401이 아니라 200이 내려온다")
    void swaggerUiIsServedWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
