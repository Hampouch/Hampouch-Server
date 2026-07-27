package Hampouch.server.domain.challenge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/challenges/{id}/focus-categories 요청 — 전체 교체.
 * categories 누락은 "전부 해제"와 구분할 수 없어 400, 빈 배열은 허용(⚠️잠정 — 명세 공백).
 * 50자는 저장 컬럼(challenge_weak_category.category) 길이 — 없으면 초과분이 저장 단계에서 500이 된다.
 */
public record FocusCategoriesRequest(

        @NotNull
        List<@NotBlank @Size(max = 50) String> categories
) {
}
