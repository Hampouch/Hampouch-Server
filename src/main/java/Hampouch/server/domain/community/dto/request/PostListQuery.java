package Hampouch.server.domain.community.dto.request;

import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;

public record PostListQuery(
        String sortType,
        int page,
        int size
) {
    public static PostListQuery of(String sortType, int page, int size) {
        if (page < 0) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new CustomException(CommonErrorCode.VALIDATION_ERROR, "size는 1 이상이어야 합니다.");
        }
        return new PostListQuery(
                sortType == null ? "LATEST" : sortType,
                page,
                size
        );
    }
}