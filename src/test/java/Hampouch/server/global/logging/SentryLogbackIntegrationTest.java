package Hampouch.server.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.logback.SentryAppender;
import io.sentry.spring.boot4.SentryLogbackInitializer;
import io.sentry.spring.boot4.SentryProperties;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SentryLogbackIntegrationTest {

    private static final String TEST_LOGGER_NAME =
            "Hampouch.server.global.logging.SentryLogbackIntegrationTest.target";

    private final Logger logger = (Logger) LoggerFactory.getLogger(TEST_LOGGER_NAME);

    @BeforeEach
    void setUp() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.ERROR);
    }

    @AfterEach
    void tearDown() {
        Sentry.close();
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    @Test
    @DisplayName("Sentry 로깅은 기본적으로 ROOT 로거를 대상으로 하고, 초기화해도 기존 CONSOLE appender를 유지하면서 ERROR 로그를 Sentry 이벤트로 전달한다")
    void addsSentryAppenderWithoutReplacingExistingAppenderAndCapturesError() {
        SentryProperties properties = new SentryProperties();
        assertThat(properties.getLogging().getLoggers())
                .containsExactly(org.slf4j.Logger.ROOT_LOGGER_NAME);
        properties.getLogging().setLoggers(List.of(TEST_LOGGER_NAME));

        ListAppender<ILoggingEvent> consoleAppender = new ListAppender<>();
        consoleAppender.setName("CONSOLE");
        consoleAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        consoleAppender.start();
        logger.addAppender(consoleAppender);

        SentryLogbackInitializer initializer = new SentryLogbackInitializer(properties);
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.refresh();
            initializer.onApplicationEvent(new ContextRefreshedEvent(context));
        }

        assertThat(logger.getAppender("CONSOLE")).isSameAs(consoleAppender);
        assertThat(logger.getAppender("SENTRY_APPENDER")).isInstanceOf(SentryAppender.class);

        AtomicReference<SentryEvent> capturedEvent = new AtomicReference<>();
        Sentry.init(
                options -> {
                    options.setDsn("https://public@example.com/1");
                    options.setTransportFactory(
                            (ignoredOptions, ignoredRequestDetails) -> new TestTransport());
                    options.setBeforeSend(
                            (event, hint) -> {
                                capturedEvent.set(event);
                                return event;
                            });
                });

        logger.error("synthetic 500 error", new IllegalStateException("expected"));

        assertThat(consoleAppender.list).hasSize(1);
        assertThat(capturedEvent.get()).isNotNull();
        assertThat(capturedEvent.get().getThrowable()).isNotNull();
    }

    private static final class TestTransport implements ITransport {

        @Override
        public void send(SentryEnvelope envelope, Hint hint) {}

        @Override
        public void flush(long timeoutMillis) {}

        @Override
        public RateLimiter getRateLimiter() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public void close(boolean isRestarting) {}
    }
}
