package Hampouch.server.domain.expense.entity;

import Hampouch.server.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expense 생성/수정 시 category/emotion의 ETC 흡수 규칙과
 * assignCustomCategory/assignCustomEmotion의 불변식 가드를 엔티티 단위로 검증.
 */
class ExpenseTest {

    private static User user(Long id) {
        User user = User.createLocalUser("u" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // ---------- of() — 생성 시 ETC 흡수 ----------

    @Test
    @DisplayName("category/emotion을 null로 생성하면(건너뛰기) ETC로 흡수되어 컬럼 자체는 계속 NOT NULL 상태를 유지한다")
    void of_absorbsNullCategoryAndEmotionIntoEtc() {
        Expense expense = Expense.of("스타벅스", 5000, null, null, LocalDate.of(2026, 6, 5), user(1L));

        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.ETC);
        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.ETC);
    }

    @Test
    @DisplayName("category/emotion을 명시하면 흡수 없이 그대로 저장된다")
    void of_keepsExplicitCategoryAndEmotion() {
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.STRESS);
    }

    @Test
    @DisplayName("name을 null로 생성해도(건너뛰기) 예외 없이 그대로 null로 저장된다")
    void of_allowsNullName() {
        Expense expense = Expense.of(null, 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        assertThat(expense.getName()).isNull();
    }

    // ---------- update() — 수정 시에도 동일한 흡수 규칙 ----------

    @Test
    @DisplayName("update()에서도 category/emotion을 null로 넘기면(건너뛰기) ETC로 흡수된다")
    void update_absorbsNullCategoryAndEmotionIntoEtc() {
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        expense.update("스타벅스 아메리카노", 6000, null, null, LocalDate.of(2026, 6, 6));

        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.ETC);
        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.ETC);
    }

    // ---------- assignCustomCategory / assignCustomEmotion 불변식 가드 ----------

    @Test
    @DisplayName("category가 ETC가 아닌데 customCategory를 넘기면 IllegalArgumentException을 던진다")
    void assignCustomCategory_rejectsNonEtcCategoryWithValue() {
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        assertThatThrownBy(() -> expense.assignCustomCategory("스터디카페"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("category가 ETC가 아니어도 customCategory를 null로 해제하는 건 허용된다")
    void assignCustomCategory_allowsNullRegardlessOfCategory() {
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        expense.assignCustomCategory(null);

        assertThat(expense.getCustomCategory()).isNull();
    }

    @Test
    @DisplayName("emotion이 ETC가 아닌데 customEmotion을 넘기면 IllegalArgumentException을 던진다 — customCategory와 대칭 케이스")
    void assignCustomEmotion_rejectsNonEtcEmotionWithValue() {
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS,
                LocalDate.of(2026, 6, 5), user(1L));

        assertThatThrownBy(() -> expense.assignCustomEmotion("억울해서"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
