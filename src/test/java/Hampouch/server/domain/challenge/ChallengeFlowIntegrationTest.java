package Hampouch.server.domain.challenge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 스택(컨트롤러→서비스→JPA→H2) 통합 — 생성 → 일별 입력 → 현황 → 캘린더 happy path.
 * 실제 HTTP 직렬화·검증·영속화를 한 번에 검증(MySQL 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChallengeFlowIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    @Test
    @DisplayName("생성부터 일별 입력(성공·초과), 현황, 캘린더 조회까지 전체 흐름이 실제 스택으로 끝까지 동작한다 (통합)")
    void fullFlow() throws Exception {
        LocalDate start = LocalDate.now(); // @FutureOrPresent 통과
        LocalDate day2 = start.plusDays(1);

        // 1) 생성: 7일 / 70,000 → dailyLimit 10,000
        String created = mvc.perform(post("/api/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7,\"budgetTotal\":70000,\"startDate\":\"" + start
                                + "\",\"weakCategories\":[\"카페음료\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).path("data").path("challengeId").asLong();

        // 2) 일별 입력: 성공 1일(8,000) + 초과 1일(15,000)
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + start + "\",\"spentAmount\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dailyLimit").value(10000));
        mvc.perform(post("/api/challenges/" + id + "/days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day2 + "\",\"spentAmount\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVER"));

        // 3) 현황: 응답 형태 {code, message, data:{challenge, progress, ...}} + 집계.
        //    홈 집계는 판정 완료 구간(시작~오늘)까지만(0714 확정) — 내일(day2)의 초과 기록은 아직 미포함.
        mvc.perform(get("/api/challenges/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.dailyLimit").value(10000))
                .andExpect(jsonPath("$.data.challenge.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.progress.successDays").value(1))
                .andExpect(jsonPath("$.data.progress.overDays").value(0))
                .andExpect(jsonPath("$.data.progress.savedAmountSoFar").value(2000));

        // 4) 캘린더: 이번 달에 시작일 기록(SUCCESS) 포함
        mvc.perform(get("/api/challenges/" + id + "/calendar")
                        .param("year", String.valueOf(start.getYear()))
                        .param("month", String.valueOf(start.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[?(@.date=='" + start + "')].status", contains("SUCCESS")));
    }
}
