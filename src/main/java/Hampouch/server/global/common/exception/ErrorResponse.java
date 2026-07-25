package Hampouch.server.global.common.exception;

import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        int status,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse from(BaseErrorCode errorCode) {
        return new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage(),
                errorCode.getHttpStatus().value(),
                null
        );
    }

    public static ErrorResponse from(BaseErrorCode errorCode, String message) {
        return new ErrorResponse(
                errorCode.getCode(),
                message,
                errorCode.getHttpStatus().value(),
                null
        );
    }

    public static ErrorResponse validation() {
        return from(CommonErrorCode.VALIDATION_ERROR);
    }

    public static ErrorResponse validation(Map<String, String> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return validation();
        }

        return new ErrorResponse(
                CommonErrorCode.VALIDATION_ERROR.getCode(),
                CommonErrorCode.VALIDATION_ERROR.getMessage(),
                CommonErrorCode.VALIDATION_ERROR.getHttpStatus().value(),
                fieldErrors
        );
    }
}