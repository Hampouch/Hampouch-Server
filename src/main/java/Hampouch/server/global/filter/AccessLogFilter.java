package Hampouch.server.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
        boolean threw = false;

        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException | ServletException | IOException e) {
            // 필터 체인 안에서 처리되지 않고 전파되는 예외가 있으면, 로그를 남기지 못한 채 그대로 죽어버릴 수 있다.
            // finally에서 무조건 로그를 남기게 하되 원래 예외는 삼키지 않고 그대로 다시 던져 정상적인 예외 처리 흐름을 유지
            threw = true;
            throw e;
        } finally {
            if (!HEALTHCHECK_PATH.equals(request.getRequestURI())) {
                long duration = System.currentTimeMillis() - start;
                // 예외가 전파된 경우 response.getStatus()가 아직 확정되지 않았을 수 있어 500으로 명시하고, [THREW] 표시로 구분한다.
                int status = threw ? 500 : response.getStatus();
                accessLog.info("{} {} -> {} ({}ms){}",
                        request.getMethod(), request.getRequestURI(), status, duration,
                        threw ? " [THREW]" : "");
            }
        }
    }
}