package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.global.mysql.MySqlContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@MySqlContainerTest
@AutoConfigureMockMvc
class AuthVerificationConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @MockitoBean
    EmailSender emailSender;

    private record HttpResult(int status, String body) {
    }

    @Test
    void 동일_이메일의_인증발송이_경쟁하면_한번만_발송되고_인증행도_하나만_남는다() throws Exception {
        String email = "send-race-" + System.currentTimeMillis() + "@example.com";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResult> firstCall = executor.submit(() -> callSend(email));
            Future<HttpResult> secondCall = executor.submit(() -> callSend(email));

            HttpResult first = firstCall.get(10, TimeUnit.SECONDS);
            HttpResult second = secondCall.get(10, TimeUnit.SECONDS);

            long successCount = Stream.of(first, second)
                    .filter(result -> result.status() == 200)
                    .count();
            long tooManyRequestsCount = Stream.of(first, second)
                    .filter(result -> result.status() == 429)
                    .count();

            assertThat(successCount)
                    .as("동시 인증 발송 요청 중 정확히 하나만 성공해야 한다")
                    .isEqualTo(1);
            assertThat(tooManyRequestsCount)
                    .as("나머지 요청은 발송 제한 응답이어야 한다")
                    .isEqualTo(1);

            HttpResult failed = first.status() == 429 ? first : second;
            assertThat(failed.body()).contains("AUTH_EMAIL_SEND_TOO_FREQUENT");

            Integer rowCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM email_verifications
                WHERE email = ?
                  AND purpose = 'SIGNUP'
                """, Integer.class, email);

            assertThat(rowCount)
                    .as("동일 이메일과 목적의 인증 행은 하나만 남아야 한다")
                    .isEqualTo(1);
            verify(emailSender, times(1))
                    .send(eq(email), anyString(), eq(VerificationPurpose.SIGNUP));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 동일_인증행의_실패요청이_경쟁해도_시도횟수가_유실되지_않는다() throws Exception {
        String email = "verify-race-" + System.currentTimeMillis() + "@example.com";
        LocalDateTime now = LocalDateTime.now();

        EmailVerification verification = EmailVerification.create(
                email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10)
        );
        emailVerificationRepository.saveAndFlush(verification);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<HttpResult>> calls = IntStream.range(0, 5)
                    .mapToObj(index -> executor.submit(() -> callVerify(email, "000000")))
                    .toList();

            List<HttpResult> results = new ArrayList<>();
            for (Future<HttpResult> call : calls) {
                results.add(call.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(400);
                assertThat(result.body()).contains("AUTH_EMAIL_CODE_MISMATCH");
            });

            Integer attemptCount = jdbc.queryForObject("""
                    SELECT attempt_count
                    FROM email_verifications
                    WHERE email = ?
                      AND purpose = 'SIGNUP'
                    """, Integer.class, email);

            assertThat(attemptCount)
                    .as("동시 실패 요청 다섯 건이 모두 누적되어야 한다")
                    .isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    private HttpResult callSend(String email) {
        try {
            MvcResult result = mvc.perform(post("/api/auth/email/send")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "%s",
                                      "purpose": "SIGNUP"
                                    }
                                    """.formatted(email)))
                    .andReturn();

            return new HttpResult(
                    result.getResponse().getStatus(),
                    result.getResponse().getContentAsString()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResult callVerify(String email, String code) {
        try {
            MvcResult result = mvc.perform(post("/api/auth/email/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "%s",
                                      "code": "%s",
                                      "purpose": "SIGNUP"
                                    }
                                    """.formatted(email, code)))
                    .andReturn();

            return new HttpResult(
                    result.getResponse().getStatus(),
                    result.getResponse().getContentAsString()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}