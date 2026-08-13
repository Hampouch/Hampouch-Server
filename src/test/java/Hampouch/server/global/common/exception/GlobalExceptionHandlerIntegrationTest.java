package Hampouch.server.global.common.exception;

import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.jwt.JwtProvider;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 메서드 불일치·미매핑 경로·필수 파라미터 누락 예외는 컨트롤러에 못 닿고 MVC 단계에서 나므로,
 * 슬라이스가 아니라 실제 매핑·시큐리티 필터를 다 태운 통합으로 검증한다.
 * 로그인이 필요한 경로는 실제 액세스 토큰을 붙여 부른다 — 인증이 없으면 401이 먼저 나가 재현이 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtProvider jwtProvider;

    // GlobalExceptionHandler는 Lombok @Slf4j로 로거 이름이 클래스 풀네임(GlobalExceptionHandler)이다.
    // 이 로거에 ListAppender를 붙여, 실제로 찍힌 로그 메시지 안에 userId가 들어있는지 직접 확인한다.
    Logger handlerLogger;
    ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUpLogCapture() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        handlerLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        handlerLogger.detachAppender(listAppender);
    }

    /** 실제 서명이 붙은 액세스 토큰의 Authorization 헤더 값 — 로그인 API를 거치지 않고 발급만 빌려 쓴다. */
    private String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, UserRole.USER);
    }

    @Test
    @DisplayName("등록된 경로에 지원하지 않는 메서드로 요청하면 405 상태와 지원 메서드가 담긴 Allow 헤더로 응답한다")
    void unsupportedMethod_returns405WithAllowHeader() throws Exception {
        mvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("지원 메서드가 여러 개인 경로에서도 405 응답의 Allow 헤더에 그 메서드들이 전부 담긴다")
    void unsupportedMethod_allowHeaderListsEveryMethod() throws Exception {
        mvc.perform(patch("/api/mini-challenges").header("Authorization", bearer(1L)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("어느 매핑에도 없는 경로로 요청하면 404 상태와 리소스 없음 응답을 준다")
    void unmappedPath_returns404() throws Exception {
        mvc.perform(get("/api/challenges/zzz/zzz").header("Authorization", bearer(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.status").value(404));
    }

    // 필수 @RequestParam이 빠지면 컨트롤러 진입 전 인자 바인딩 단계에서 던져지므로,
    // 서비스·DB 접근 없이도 재현된다. /api/expenses/day는 date가 필수라 이 케이스에 그대로 쓸 수 있다.
    @Test
    @DisplayName("필수 쿼리 파라미터 없이 요청하면 400 상태와 해당 파라미터의 필드 에러로 응답한다")
    void missingRequiredQueryParameter_returns400WithFieldError() throws Exception {
        mvc.perform(get("/api/expenses/day").header("Authorization", bearer(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.date").value("date은(는) 필수 파라미터입니다."))
                .andExpect(jsonPath("$.status").value(400));
    }

    // ===== userId 로깅 검증 =====
    // GlobalExceptionHandler의 모든 핸들러가 resolveUserId()로 로그에 userId를 남기도록
    // 수정했는데, 이게 실제로 로그 문자열에 반영되는지 확인한다.

    @Test
    @DisplayName("인증된 요청에서 405가 발생하면 로그에 실제 userId가 찍힌다")
    void unsupportedMethod_logsActualUserId() throws Exception {
        mvc.perform(patch("/api/mini-challenges").header("Authorization", bearer(42L)))
                .andExpect(status().isMethodNotAllowed());

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("userId=42"));
    }

    @Test
    @DisplayName("인증된 요청에서 필수 파라미터 누락으로 400이 발생하면 로그에 실제 userId가 찍힌다")
    void missingRequiredQueryParameter_logsActualUserId() throws Exception {
        mvc.perform(get("/api/expenses/day").header("Authorization", bearer(7L)))
                .andExpect(status().isBadRequest());

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("userId=7"));
    }

    @Test
    @DisplayName("CustomException이 발생하면 로그에 실제 userId가 찍힌다")
    void customException_logsActualUserId() throws Exception {
        long nonExistentUserId = 999999L;

        mvc.perform(patch("/api/auth/nickname")
                        .header("Authorization", bearer(nonExistentUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"테스터\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(listAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getFormattedMessage()).contains("[CustomException]");
                    assertThat(event.getFormattedMessage()).contains("userId=" + nonExistentUserId);
                });
    }

    @Test
    @DisplayName("인증되지 않은 요청에서 예외가 발생하면 로그에 userId=null이 찍힌다")
    void unauthenticatedRequest_logsNullUserId() throws Exception {
        // Authorization 헤더 없이 405를 유발 (인증 불필요한 로그인 경로에 잘못된 메서드로 요청)
        mvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(listAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("userId=null"));
    }
}