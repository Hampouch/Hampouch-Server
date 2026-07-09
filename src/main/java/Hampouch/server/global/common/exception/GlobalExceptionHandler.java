package Hampouch.server.global.common.exception;

import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * 직접 정의한 비즈니스 예외 처리
     *
     * 사용 예:
     * throw new CustomException(UserErrorCode.USER_NOT_FOUND);
     * throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_FOUND);
     */
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
                request.getMethod(),
                request.getRequestURI(),
                errorCode.getHttpStatus().value(),
                errorCode.getCode(),
                userId,
                hasAuth,
                request.getHeader("User-Agent")
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.from(errorCode));
    }

    /**
     * @RequestBody + @Valid 검증 실패 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        log.warn(
                "[ValidationException] {} {} | fieldErrors={} | globalErrors={}",
                request.getMethod(),
                request.getRequestURI(),
                fieldErrors,
                e.getBindingResult().getGlobalErrors()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.validation(fieldErrors));
    }

    /**
     * @RequestParam, @PathVariable 등에 붙은 검증 실패 처리
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(
            HandlerMethodValidationException e,
            HttpServletRequest request
    ) {
        log.warn(
                "[HandlerMethodValidationException] {} {} | message={}",
                request.getMethod(),
                request.getRequestURI(),
                e.getMessage()
        );

        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ErrorResponse.from(CommonErrorCode.VALIDATION_ERROR));
    }

    /**
     * 처리하지 못한 모든 예외 처리
     * 클라이언트에는 500 에러 내려주고, 서버 로그에만 실제 예외를 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {
        log.error(
                "[UnhandledException] {} {}",
                request.getMethod(),
                request.getRequestURI(),
                e
        );

        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ErrorResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
