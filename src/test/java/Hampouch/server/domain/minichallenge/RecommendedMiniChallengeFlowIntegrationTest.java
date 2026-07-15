package Hampouch.server.domain.minichallenge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 스택(기동 시 시더→컨트롤러→서비스→JPA→H2) 통합 — 전체 조회 → 기간 필터 → 빈 결과 happy path.
 * 노출 순서가 랜덤(0630 확정)이라 순서는 단정하지 않고 구성·형태만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendedMiniChallengeFlowIntegrationTest {

    private static final String PATH = "/api/mini-challenges/recommended";

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("앱을 실제로 기동하면 시더가 채운 추천 미니 챌린지 목록이 조회된다 — 전체 조회와 7일 필터 조회가 성공하고, 허용 기간(1·3·7·14·31) 밖인 5일 조회는 빈 목록 200이 된다 (통합)")
    void fullFlow() throws Exception {
        // 1) 전체: 시더가 넣은 카탈로그가 {code, data.items[]} 형태로 내려온다
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].recommendedId").isNumber())
                .andExpect(jsonPath("$.data.items[0].title").isString())
                .andExpect(jsonPath("$.data.items[0].durationDays").isNumber());

        // 2) 기간 탭 필터: 7일 — 내려온 항목 전부가 7일짜리
        mvc.perform(get(PATH).param("durationDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andExpect(jsonPath("$.data.items[*].durationDays", everyItem(is(7))));

        // 3) 화이트리스트 밖(5일): 에러가 아니라 빈 목록 200 (자체 결정 — 서비스 주석 참조)
        mvc.perform(get(PATH).param("durationDays", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }
}
