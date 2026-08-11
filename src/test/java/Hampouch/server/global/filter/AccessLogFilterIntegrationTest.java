package Hampouch.server.global.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AccessLogFilter가 정상 요청뿐 아니라, Security에서 401/403으로 짧게 끊기는 요청과
 * 필터 체인 안에서 예외가 전파되는 요청에도 빠짐없이 로그를 남기는지 검증한다.
 * ACCESS_LOG 로거에 ListAppender를 붙여 실제로 기록된 로그 이벤트를 메모리에서 직접 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessLogFilterIntegrationTest {

    @Autowired
    MockMvc mvc;

    Logger accessLogger;
    ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        accessLogger = (Logger) LoggerFactory.getLogger("ACCESS_LOG");
        listAppender = new ListAppender<>();
        listAppender.start();
        accessLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(listAppender);
    }

    @Test
    void 인증없이_호출해_401이_나도_ACCESS_LOG에_남는다() throws Exception {
        // 인증이 필요한 API를 토큰 없이 호출 -> Security 필터 단계에서 401로 짧게 끊김.
        // AccessLogFilter가 Security보다 먼저 실행되지 않으면 이 요청은 로그에 안 남는다.
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("GET /api/auth/me -> 401"));
    }

    @Test
    void 잘못된_요청형식으로_400이_나도_ACCESS_LOG에_남는다() throws Exception {
        // 검증 실패(400)도 정상 흐름 내에서 응답이 만들어지는 케이스라, 이건 필터 순서와
        // 무관하게 원래도 로그가 남아야 정상이다. 회귀 확인 차원에서 같이 검증한다.
        mvc.perform(post("/api/auth/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().isBadRequest());

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("POST /api/auth/email/send -> 400"));
    }
}