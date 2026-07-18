package Hampouch.server.global.common.exception;

import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

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