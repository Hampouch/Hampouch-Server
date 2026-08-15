package Hampouch.server.domain.community.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FoodPostRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,

        @NotBlank(message = "메뉴 이름은 필수입니다.")
        @Size(max = 100, message = "메뉴 이름은 최대 100자까지 입력할 수 있습니다.")
        String menuName,

        @NotBlank(message = "장소는 필수입니다.")
        @Size(max = 100, message = "장소는 최대 100자까지 입력할 수 있습니다.")
        String placeName,

        @NotNull(message = "가격은 필수입니다.")
        @PositiveOrZero(message = "가격은 0원 이상이어야 합니다.")
        Integer price,

        @NotNull(message = "맛 별점은 필수입니다.")
        @Min(value = 1, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        @Max(value = 5, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        Integer tasteRating,

        @NotNull(message = "가성비 별점은 필수입니다.")
        @Min(value = 1, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        @Max(value = 5, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        Integer costRating,

        @NotNull(message = "분위기 별점은 필수입니다.")
        @Min(value = 1, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        @Max(value = 5, message = "별점은 1점 이상 5점 이하만 가능합니다.")
        Integer moodRating,

        @NotBlank(message = "내용은 필수입니다.")
        String content,

        @Size(max = 5, message = "이미지는 최대 5장까지 등록할 수 있습니다.")
        List<
                @NotBlank(message = "이미지 key는 비어 있을 수 없습니다.")
                @Pattern(
                        regexp = "^community/posts/[^/]+\\.(jpg|png|webp)$",
                        message = "올바른 이미지 key 형식이 아닙니다."
                )
                        String
                > imageKeys
) {
}