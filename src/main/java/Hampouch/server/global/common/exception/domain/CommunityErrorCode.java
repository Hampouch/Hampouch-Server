package Hampouch.server.global.common.exception.domain;

import Hampouch.server.global.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommunityErrorCode implements BaseErrorCode {

    COMMUNITY_POST_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_POST_NOT_FOUND", "게시글을 찾을 수 없습니다."),
    COMMUNITY_INVALID_SORT_TYPE(HttpStatus.BAD_REQUEST, "COMMUNITY_INVALID_SORT_TYPE", "올바르지 않은 정렬 기준입니다."),
    COMMUNITY_INVALID_POST_CATEGORY(HttpStatus.BAD_REQUEST, "COMMUNITY_INVALID_POST_CATEGORY", "올바르지 않은 게시글 카테고리입니다."),
    COMMUNITY_IMAGE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "COMMUNITY_IMAGE_COUNT_EXCEEDED", "이미지는 최대 5장까지 등록할 수 있습니다."),
    COMMUNITY_DUPLICATE_IMAGE(HttpStatus.BAD_REQUEST, "COMMUNITY_DUPLICATE_IMAGE", "중복된 이미지는 등록할 수 없습니다."),
    COMMUNITY_INVALID_BATTLE_URL(HttpStatus.BAD_REQUEST, "COMMUNITY_INVALID_BATTLE_URL", "올바르지 않은 햄배틀 URL입니다."),
    COMMUNITY_BATTLE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_BATTLE_NOT_FOUND", "햄배틀을 찾을 수 없습니다."),
    COMMUNITY_POST_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "COMMUNITY_POST_TYPE_MISMATCH", "게시글 타입이 요청 API와 일치하지 않습니다."),
    COMMUNITY_NOT_POST_AUTHOR(HttpStatus.FORBIDDEN, "COMMUNITY_NOT_POST_AUTHOR", "게시글 작성자만 수정하거나 삭제할 수 있습니다."),
    COMMUNITY_PARENT_COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_PARENT_COMMENT_NOT_FOUND", "부모 댓글을 찾을 수 없습니다."),
    COMMUNITY_PARENT_COMMENT_POST_MISMATCH(HttpStatus.BAD_REQUEST, "COMMUNITY_PARENT_COMMENT_POST_MISMATCH", "부모 댓글이 해당 게시글에 속하지 않습니다."),
    COMMUNITY_COMMENT_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "COMMUNITY_COMMENT_DEPTH_EXCEEDED", "대댓글에는 답글을 작성할 수 없습니다."),
    COMMUNITY_COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다."),
    COMMUNITY_NOT_COMMENT_AUTHOR(HttpStatus.FORBIDDEN, "COMMUNITY_NOT_COMMENT_AUTHOR", "댓글 작성자만 삭제할 수 있습니다."),
    COMMUNITY_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "COMMUNITY_IMAGE_UPLOAD_FAILED", "이미지 업로드 처리 중 오류가 발생했습니다."),
    COMMUNITY_IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "COMMUNITY_IMAGE_SIZE_EXCEEDED", "이미지 크기는 최대 10MB까지 등록할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
