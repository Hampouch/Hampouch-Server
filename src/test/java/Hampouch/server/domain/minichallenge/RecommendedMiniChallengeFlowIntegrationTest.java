package Hampouch.server.domain.minichallenge;

import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.jwt.JwtProvider;
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
 * 카탈로그는 유저 식별을 안 받지만 로그인은 필요하다(명세의 401) — 실제 액세스 토큰을 자체 발급해 부른다.
 * 발급만 빌려 쓰는 것이라 로그인 API는 거치지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendedMiniChallengeFlowIntegrationTest {

    private static final String PATH = "/api/mini-challenges/recommended";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtProvider jwtProvider;

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    @Test
    @DisplayName("액세스 토큰 없이 추천 미니 챌린지 카탈로그를 조회하면 401과 인증 필요 에러 본문으로 거절된다 — 내려가는 내용이 유저마다 다르지 않더라도 로그인 자체는 필요하기 때문")
    void recommendedRejectsRequestWithoutToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("앱을 실제로 기동하면 시더가 채운 추천 미니 챌린지 목록이 조회된다 — 전체 조회와 7일 필터 조회가 성공하고, 허용 기간(1·3·7·14·31) 밖인 5일 조회는 빈 목록 200이 된다")
    void fullFlow() throws Exception {
        // 1) 전체: 시더가 넣은 카탈로그가 {code, data.items[]} 형태로 내려온다
        mvc.perform(get(PATH).header("Authorization", bearer(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].recommendedId").isNumber())
                .andExpect(jsonPath("$.data.items[0].title").isString())
                .andExpect(jsonPath("$.data.items[0].durationDays").isNumber());

        // 2) 기간 탭 필터: 7일 — 내려온 항목 전부가 7일짜리
        mvc.perform(get(PATH).header("Authorization", bearer(1L)).param("durationDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andExpect(jsonPath("$.data.items[*].durationDays", everyItem(is(7))));

        // 3) 화이트리스트 밖(5일): 에러가 아니라 빈 목록 200 (자체 결정 — 서비스 주석 참조)
        mvc.perform(get(PATH).header("Authorization", bearer(1L)).param("durationDays", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }
}
