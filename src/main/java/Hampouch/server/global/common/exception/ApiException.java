package Hampouch.server.global.common.exception;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final BaseErrorCode baseErrorCode;

    public ApiException(BaseErrorCode baseErrorCode) {
        super(baseErrorCode.getMessage());
        this.baseErrorCode = baseErrorCode;
    }

    public ApiException(BaseErrorCode baseErrorCode, String message) {
        super(message);
        this.baseErrorCode = baseErrorCode;
    }
}
