package Hampouch.server.global.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 필터 체인 안에서 처리되지 않은 예외가 전파되는 경우에도
 * AccessLogFilter가 원래 예외를 그대로 다시 던지면서(rethrow) 로그도 남기는지 검증한다.
 * (실제 API로 500을 재현하려면 DB를 끄는 등 별도 조작이 필요해, 필터 자체를 직접
 *  단위 테스트하는 방식으로 확인한다)
 */
class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

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
    void 필터체인에서_예외가_전파돼도_원래_예외를_다시_던지고_로그를_남긴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);
        RuntimeException originalException = new RuntimeException("test exception");
        doThrow(originalException).when(filterChain).doFilter(request, response);

        // 원래 예외가 삼켜지지 않고 그대로(같은 인스턴스) 다시 던져지는지 확인
        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isSameAs(originalException);

        // 예외로 끝났어도 ACCESS_LOG에 로그가 남았는지, [THREW] 표시가 있는지 확인
        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("GET /api/test")
                        && event.getFormattedMessage().contains("[THREW]"));
    }

    @Test
    void 정상_처리되면_실제_상태코드로_로그가_남는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain filterChain = mock(FilterChain.class); // 아무것도 안 던지면 정상 통과

        filter.doFilter(request, response, filterChain);

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("GET /api/test -> 200")
                        && !event.getFormattedMessage().contains("[THREW]"));
    }

    @Test
    void healthcheck_경로는_로그에_안_남는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(listAppender.list).isEmpty();
    }
}