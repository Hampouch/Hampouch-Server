package Hampouch.server.domain.challenge.exception;

import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;

public final class ChallengeNotClosableException extends CustomException {

    public ChallengeNotClosableException() {
        super(ChallengeErrorCode.CHALLENGE_NOT_CLOSABLE);
    }
}
