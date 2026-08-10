package Hampouch.server.domain.expense.dto;

import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

/**
 * 지출 수정 요청(PUT /expenses/{id}).
 * ExpenseCreateRequest와 필드 구성이 거의 같지만 imageKey는 없다 — 수정 API는 그 필드 자체를 받지 않는다
 */
public record ExpenseUpdateRequest(

        @Size(max = 90)
        String name,

        @NotNull
        @Min(0)
        Integer price,

        ExpenseCategory category,

        @Size(max = 50)
        String customCategory, // category=ETC일 때만 사용하는 자유 입력 태그 — 그 외엔 null이어야 함

        ExpenseEmotion emotion, // 건너뛰면 null — category와 동일한 흡수 규칙

        @Size(max = 50)
        String customEmotion, // emotion=ETC일 때만 사용 — 위와 동일한 이유로 nullable

        @NotNull @PastOrPresent // 미래 날짜의 지출 입력 방지 — 오늘까지만 허용
        LocalDate date,

        @Size(max = 300)
        String memo // 빈 문자열/null이면 저장하지 않음(정규화는 서비스 계층에서)
) {

    /** ExpenseCreateRequest.isCategoryConsistent()와 동일 규칙 */
    @AssertTrue(message = "category가 ETC일 때만 customCategory를 입력할 수 있습니다.")
    public boolean isCategoryConsistent() {
        boolean hasCustomCategory = customCategory != null && !customCategory.isBlank();
        return (category == ExpenseCategory.ETC) == hasCustomCategory;
    }

    /** ExpenseCreateRequest.isEmotionConsistent()와 동일 규칙 */
    @AssertTrue(message = "emotion이 ETC일 때만 customEmotion을 입력할 수 있습니다.")
    public boolean isEmotionConsistent() {
        boolean hasCustomEmotion = customEmotion != null && !customEmotion.isBlank();
        return (emotion == ExpenseEmotion.ETC) == hasCustomEmotion;
    }
}