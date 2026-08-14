package Hampouch.server.global.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("Authorization 헤더 없이 /v3/api-docs를 열면 200과 함께 컨트롤러에서 수집한 경로가 담긴 문서가 내려온다")
    void apiDocsAreServedWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists());
    }

    @Test
    @DisplayName("목표 조정 요청의 OpenAPI enum에는 진행 중 화면에서 허용하는 10%·20%·30% 옵션이 모두 노출된다")
    void adjustGoalRequestDocumentsAllPresetOptions() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.components.schemas.AdjustGoalRequest.properties.option.enum",
                        contains("PLUS_10", "PLUS_20", "PLUS_30")));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 Swagger UI 경로를 열면 401이 아니라 200이 내려온다")
    void swaggerUiIsServedWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("보호 API는 Bearer 인증을 요구하고 LoginUserId는 query parameter로 노출하지 않는다")
    void protectedApisDocumentBearerAuthWithoutUserIdQuery() throws Exception {
        String content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = objectMapper.readTree(content);

        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/bearerFormat").asText()).isEqualTo("JWT");
        assertThat(document.at("/paths/~1api~1auth~1me/get/security/0/bearerAuth").isArray()).isTrue();
        assertThat(document.at("/paths/~1api~1rests/post/security/0/bearerAuth").isArray()).isTrue();
        assertThat(document.at("/paths/~1api~1mini-challenges~1recommended/get/security/0/bearerAuth").isArray()).isTrue();
        assertThat(document.at("/paths/~1api~1auth~1login/post").has("security")).isFalse();
        assertThat(document.findParents("name")).noneMatch(parameter ->
                "query".equals(parameter.path("in").asText())
                        && "userId".equals(parameter.path("name").asText())
        );
    }
}
