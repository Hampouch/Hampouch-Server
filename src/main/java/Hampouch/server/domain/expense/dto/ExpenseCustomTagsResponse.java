package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.CustomCategory;
import Hampouch.server.domain.expense.entity.CustomEmotion;

import java.util.List;

/**
 * GET /expenses/custom-tags 응답
 * 고정 enum(ExpenseCategory/ExpenseEmotion) 값은 front에서 보유 중이라는 가정, 이 응답은 유저가 직접
 * 등록한 커스텀 카테고리/감정 태그만 책임진다. 태그가 하나도 없어도 에러가 아니라 빈 배열로 정상 응답
 */
public record ExpenseCustomTagsResponse(
        List<Tag> emotions,
        List<Tag> categories
) {

    /** id를 함께 내려주는 이유: 추후 커스텀 태그 수정/삭제 API가 생기면 프론트가 이 id로 대상을 지정해야 하기 때문. */
    public record Tag(Long id, String name) {
        private static Tag from(CustomEmotion emotion) {
            return new Tag(emotion.getId(), emotion.getName());
        }

        private static Tag from(CustomCategory category) {
            return new Tag(category.getId(), category.getName());
        }
    }

    public static ExpenseCustomTagsResponse of(List<CustomEmotion> emotions, List<CustomCategory> categories) {
        return new ExpenseCustomTagsResponse(
                emotions.stream().map(Tag::from).toList(),
                categories.stream().map(Tag::from).toList());
    }
}
