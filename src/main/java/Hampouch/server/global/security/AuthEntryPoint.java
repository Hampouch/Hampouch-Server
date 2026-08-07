// AuthEntryPoint.java (구 CustomAuthenticationEntryPoint)
package Hampouch.server.global.security;

import Hampouch.server.global.common.exception.ErrorResponse;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        log.warn(
                "[AuthEntryPoint] 인증 실패: {} {} | reason={}",
                request.getMethod(),
                request.getRequestURI(),
                authException.getMessage()
        );

        ErrorResponse errorResponse = ErrorResponse.from(AuthErrorCode.AUTH_UNAUTHORIZED);

        response.setStatus(AuthErrorCode.AUTH_UNAUTHORIZED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}