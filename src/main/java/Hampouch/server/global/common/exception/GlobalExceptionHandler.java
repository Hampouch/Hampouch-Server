package Hampouch.server.global.common.exception;

import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Object userId = request.getAttribute("userId");

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
                "[ValidationException] {} {} | fieldErrors={} | globalErrors={}",
                request.getMethod(), request.getRequestURI(),
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
                "[HandlerMethodValidationException] {} {} | fieldErrors={} | message={}",
                request.getMethod(), request.getRequestURI(),
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
                "[ConstraintViolationException] {} {} | fieldErrors={}",
                request.getMethod(), request.getRequestURI(), fieldErrors
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
                "[MethodArgumentTypeMismatchException] {} {} | field={} | value={}",
                request.getMethod(), request.getRequestURI(),
                fieldName, e.getValue()
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
                "[HttpMessageNotReadableException] {} {} | message={}",
                request.getMethod(), request.getRequestURI(), e.getMessage()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {
        log.error("[UnhandledException] {} {}", request.getMethod(), request.getRequestURI(), e);

        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ErrorResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}