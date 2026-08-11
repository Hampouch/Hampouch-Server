package Hampouch.server.domain.auth;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.domain.auth.repository.EmailVerificationRepository;
import Hampouch.server.domain.auth.util.EmailSender;
import Hampouch.server.domain.auth.util.SocialTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 동시 요청 상황에서의 경쟁 상태(race condition)를 검증한다.
 * 이 테스트들은 @Transactional을 클래스에 걸지 않는다 - 각 스레드가 별도의 실제
 * 트랜잭션/커넥션으로 요청을 보내야 경쟁 상태가 실제로 재현되기 때문이다
 * (클래스 레벨 @Transactional로 감싸면 모든 요청이 하나의 트랜잭션을 공유하게 되어
 *  의미가 없어진다). 대신 각 테스트가 만든 데이터는 테스트 끝에서 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EmailVerificationRepository emailVerificationRepository;

    @MockitoBean
    EmailSender emailSender;

    @MockitoBean(name = "googleVerifier")
    SocialTokenVerifier socialTokenVerifier;

    @Test
    void 동시에_같은_refreshToken으로_재발급하면_하나만_성공한다() throws Exception {
        String email = "concurrent-refresh-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);

        mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"동시성테스터\"}"));

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(post("/api/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount = statusCodes.stream().filter(status -> status == 200).count();
        assertThat(successCount)
                .as("두 요청 중 정확히 하나만 재발급에 성공해야 한다. 실제 응답: %s", statusCodes)
                .isEqualTo(1);
    }

    @Test
    void 동시에_같은_이메일로_회원가입하면_하나만_성공하고_500은_없다() throws Exception {
        String email = "concurrent-signup-" + System.currentTimeMillis() + "@example.com";
        prepareVerifiedEmail(email);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    MvcResult result = mvc.perform(post("/api/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"" + email + "\",\"password\":\"password1\",\"nickname\":\"동시성" + idx + "\"}"))
                            .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount = statusCodes.stream().filter(status -> status == 200).count();

        assertThat(successCount)
                .as("두 요청 중 정확히 하나만 회원가입에 성공해야 한다. 실제 응답: %s", statusCodes)
                .isEqualTo(1);
        assertThat(statusCodes)
                .as("나머지 하나는 409여야 하며 500이 나오면 안 된다. 실제 응답: %s", statusCodes)
                .doesNotContain(500)
                .contains(409);
    }

    private void prepareVerifiedEmail(String email) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = EmailVerification.create(email, "123456", VerificationPurpose.SIGNUP, now.plusMinutes(10));
        verification.verify(now);
        emailVerificationRepository.save(verification);
    }
}