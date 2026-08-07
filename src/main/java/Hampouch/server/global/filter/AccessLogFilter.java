package Hampouch.server.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccessLogFilter extends OncePerRequestFilter {

    // "ACCESS_LOG"라는 별도 이름의 로거 - logback 설정에서 이 이름을 기준으로 콘솔(docker logs)이 아니라 파일로만 보내도록 분리
    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS_LOG");
    private static final String HEALTHCHECK_PATH = "/actuator/health";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        filterChain.doFilter(request, response);

        // healthcheck 제외
        if (!HEALTHCHECK_PATH.equals(request.getRequestURI())) {
            long duration = System.currentTimeMillis() - start;
            accessLog.info("{} {} -> {} ({}ms)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        }
    }
}