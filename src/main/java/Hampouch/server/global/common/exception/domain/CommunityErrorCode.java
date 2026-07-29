package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommunityErrorCode implements BaseErrorCode {

    COMMUNITY_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "COMMUNITY_IMAGE_UPLOAD_FAILED", "이미지 업로드 처리 중 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}