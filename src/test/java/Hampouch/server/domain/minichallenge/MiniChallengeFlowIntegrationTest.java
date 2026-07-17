package Hampouch.server.domain.minichallenge;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 전체 스택(컨트롤러→서비스→JPA→H2) 통합 — 생성 → 체크 → 그날 집계 조회 → 해제(멱등) → 삭제 happy path.
 * 실제 HTTP 직렬화·검증·영속화를 한 번에 검증(#1 ChallengeFlowIntegrationTest와 동일 구성, MySQL 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiniChallengeFlowIntegrationTest {

    /** 같은 H2를 쓰는 다른 통합 테스트 데이터와 섞이지 않게 이 흐름 전용 유저 id를 쓴다. */
    private static final String USER = "9";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    RecommendedMiniChallengeRepository recommendedMiniChallengeRepository;

    @Test
    @DisplayName("생성 → 오늘 체크 → 그날 조회가 '1/1 완료·연속 1일째'를 돌려주는지 확인 → 해제 두 번(멱등 200) → 삭제(204) → 지운 미니 재삭제(404)까지, 미니 하나의 전체 흐름이 모킹 없이 실제 스택으로 끝까지 동작한다")
    void fullFlow() throws Exception {
        // 서버의 "오늘"은 ClockConfig(Asia/Seoul) 기준 — 머신 시간대가 달라도 어긋나지 않게 같은 기준으로 계산
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 1) 커스텀 생성: 시작=오늘, 종료=오늘+6 (7일)
        String created = mvc.perform(post("/api/mini-challenges")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"custom\":{\"title\":\"오늘 커피 사먹지 않기\",\"durationDays\":7}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.durationDays").value(7))
                .andExpect(jsonPath("$.data.startDate").value(today.toString()))
                .andExpect(jsonPath("$.data.endDate").value(today.plusDays(6).toString()))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("miniChallengeId").asLong();

        // 2) 체크 (date 생략 = 오늘)
        mvc.perform(put("/api/mini-challenges/" + id + "/check")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checked").value(true))
                .andExpect(jsonPath("$.data.date").value(today.toString()));

        // 3) 그날 집계: 1/1 체크·스트릭 1일차 — 저장 없이 조회 시 계산
        mvc.perform(get("/api/mini-challenges").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(today.toString()))
                .andExpect(jsonPath("$.data.summary.checkedCount").value(1))
                .andExpect(jsonPath("$.data.summary.totalCount").value(1))
                .andExpect(jsonPath("$.data.summary.streakDays").value(1))
                .andExpect(jsonPath("$.data.items[0].miniChallengeId").value(id))
                .andExpect(jsonPath("$.data.items[0].progressDays").value(1))
                .andExpect(jsonPath("$.data.items[0].itemStreak").value(1))
                .andExpect(jsonPath("$.data.items[0].checked").value(true));

        // 4) 해제 — 두 번 보내도 멱등으로 200
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/mini-challenges/" + id + "/check")
                            .header("X-User-Id", USER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"checked\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checked").value(false));
        }
        mvc.perform(get("/api/mini-challenges").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.summary.checkedCount").value(0))
                .andExpect(jsonPath("$.data.summary.streakDays").value(0))
                .andExpect(jsonPath("$.data.items[0].checked").value(false));

        // 5) 삭제 → 204(바디 없음), 목록에서 사라짐
        mvc.perform(delete("/api/mini-challenges/" + id).header("X-User-Id", USER))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(get("/api/mini-challenges").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.summary.totalCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 6) 재삭제 → 404 (행이 이미 없음)
        mvc.perform(delete("/api/mini-challenges/" + id).header("X-User-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINI_NOT_FOUND"));
    }

    @Test
    @DisplayName("추천에서 추가하면 카탈로그 행의 제목·기간이 복사된 유저 소유 미니가 새 id로 만들어지고, 없는 recommendedId는 404가 난다 (#19 배선, 통합)")
    void recommendedWiringFlow() throws Exception {
        // fullFlow(유저 9)의 집계 단언과 데이터가 섞이지 않게 이 시나리오 전용 유저를 따로 쓴다
        String user = "19";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 시더(기획 임시 12건)의 내용·id에 기대지 않도록 이 테스트 전용 카탈로그 행을 직접 심는다 — 시드가 바뀌어도 안 깨짐
        RecommendedMiniChallenge rec = recommendedMiniChallengeRepository.save(
                RecommendedMiniChallenge.of("통합 테스트용 추천 미니", 3));

        // 1) 추천에서 추가: 카탈로그 값 복사 + 시작일=오늘, 새 miniChallengeId 발급(카탈로그 id와 별개)
        String created = mvc.perform(post("/api/mini-challenges")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendedId\":" + rec.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.title").value("통합 테스트용 추천 미니"))
                .andExpect(jsonPath("$.data.durationDays").value(3))
                .andExpect(jsonPath("$.data.startDate").value(today.toString()))
                .andExpect(jsonPath("$.data.endDate").value(today.plusDays(2).toString()))
                .andReturn().getResponse().getContentAsString();
        long miniId = om.readTree(created).path("data").path("miniChallengeId").asLong();

        // 2) 만들어진 건 유저 미니라 체크·삭제가 miniChallengeId로 동작한다
        mvc.perform(put("/api/mini-challenges/" + miniId + "/check")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/mini-challenges/" + miniId).header("X-User-Id", user))
                .andExpect(status().isNoContent());

        // 3) 유저 미니가 지워져도 카탈로그 행은 그대로다 — 값 복사(FK 없음)의 확인
        mvc.perform(post("/api/mini-challenges")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendedId\":" + rec.getId() + "}"))
                .andExpect(status().isCreated());

        // 4) 카탈로그에 없는 id는 404
        mvc.perform(post("/api/mini-challenges")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendedId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINI_RECOMMENDED_NOT_FOUND"));
    }
}
