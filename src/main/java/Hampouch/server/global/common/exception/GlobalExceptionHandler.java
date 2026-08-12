package Hampouch.server.global.common.exception;

import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(
            CustomException e,
            HttpServletRequest request
    ) {
        BaseErrorCode errorCode = e.getErrorCode();

        String auth = request.getHeader("Authorization");
        boolean hasAuth = auth != null && !auth.isBlank();
        Object userId = resolveUserId();

        log.warn(
                "[CustomException] {} {} | status={} code={} | userId={} | hasAuth={} | ua={}",
                request.getMethod(), request.getRequestURI(),
                errorCode.getHttpStatus().value(), errorCode.getCode(),
                userId, hasAuth, request.getHeader("User-Agent")
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.from(errorCode, e.getMessage()));
    }

    // JwtFilter가 SecurityContext에 심어둔 인증 정보에서 userId를 꺼낸다.
    // principal이 JwtFilter에서 넣어준 Long 타입일 때만 값을 채우고, 인증이 없거나(anonymous 등) principal 타입이 다르면 null로 남긴다.
    private Object resolveUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long principalUserId) {
            return principalUserId;
        }
        return null;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        log.warn(
                "[ValidationException] {} {} | userId={} | fieldErrors={} | globalErrors={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(),
                fieldErrors, e.getBindingResult().getGlobalErrors()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(
            HandlerMethodValidationException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        e.getParameterValidationResults().forEach(result -> {
            String paramName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                String message = error.getDefaultMessage();
                if (paramName != null && message != null) {
                    fieldErrors.putIfAbsent(paramName, message);
                }
            });
        });

        log.warn(
                "[HandlerMethodValidationException] {} {} | userId={} | fieldErrors={} | message={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(),
                fieldErrors, e.getMessage()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String propertyPath = violation.getPropertyPath().toString();
            String fieldName = propertyPath.contains(".")
                    ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1)
                    : propertyPath;
            fieldErrors.putIfAbsent(fieldName, violation.getMessage());
        }

        log.warn(
                "[ConstraintViolationException] {} {} | userId={} | fieldErrors={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(), fieldErrors
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String fieldName = e.getName();
        String requiredType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "값";
        fieldErrors.put(fieldName, fieldName + "은(는) " + requiredType + " 형식이어야 합니다.");

        log.warn(
                "[MethodArgumentTypeMismatchException] {} {} | userId={} | field={} | value={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(),
                fieldName, e.getValue()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    // 필수 @RequestParam이 요청에 아예 안 붙어 온 경우 — 값이 없어 바인딩 자체가 안 되므로 @Valid까지
    // 못 가고 여기서만 잡을 수 있다. 이 handler가 없으면 fallback 경로를 타 500으로 나간다.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String paramName = e.getParameterName();
        fieldErrors.put(paramName, paramName + "은(는) 필수 파라미터입니다.");

        log.warn(
                "[MissingServletRequestParameterException] {} {} | userId={} | param={} | type={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(),
                paramName, e.getParameterType()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    // 본문 JSON을 객체로 만들지 못한 경우(JSON 문법 오류·enum에 없는 값·숫자 자리에 문자열·body 누락 등) —
    // @Valid·@Pattern은 객체가 만들어진 다음에 돌기 때문에 이 단계 실패는 여기서만 잡을 수 있고,
    // 없으면 아래 Exception 핸들러로 흘러 클라이언트 입력 오류가 500으로 나간다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        log.warn(
                "[HttpMessageNotReadableException] {} {} | userId={} | message={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(), e.getMessage()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation());
    }

    // 경로는 맞는데 메서드가 지원 밖인 요청 — 스프링 기본 처리가 붙여 주던 Allow 헤더는 advice가 먼저 잡으면
    // 사라지므로(RFC 9110이 405에 Allow를 요구), 예외가 들고 있는 지원 메서드 목록으로 직접 붙인다.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e,
            HttpServletRequest request
    ) {
        log.warn(
                "[HttpRequestMethodNotSupportedException] {} {} | userId={} | supported={}",
                request.getMethod(), request.getRequestURI(), resolveUserId(), e.getSupportedHttpMethods()
        );

        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(CommonErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        Set<HttpMethod> supportedMethods = e.getSupportedHttpMethods();
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            response.allow(supportedMethods.toArray(HttpMethod[]::new));
        }
        return response.body(ErrorResponse.from(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    // 어느 매핑에도 안 걸린 경로는 NoHandlerFound가 아니라 정적 리소스 폴백의 이 예외로 온다 —
    // 없으면 아래 Exception 핸들러가 잡아 클라이언트의 경로 오타가 500으로 나간다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException e,
            HttpServletRequest request
    ) {
        log.warn(
                "[NoResourceFoundException] {} {} | userId={} ",
                request.getMethod(), request.getRequestURI(), resolveUserId()
        );

        return ResponseEntity
                .status(CommonErrorCode.NOT_FOUND.getHttpStatus())
                .body(ErrorResponse.from(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {
        log.error("[UnhandledException] {} {} | userId={}", request.getMethod(), request.getRequestURI(), resolveUserId(), e);

        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ErrorResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
