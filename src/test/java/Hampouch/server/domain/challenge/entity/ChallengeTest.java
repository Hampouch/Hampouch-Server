package Hampouch.server.domain.challenge.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Challenge 엔티티의 생성 불변식과 집중 카테고리 교체 규칙 검증. 빌더는 필수 필드를 빠뜨려도
 * build()가 컴파일되므로(누락 시 참조형 null·정수형 0), 생성자가 마지막 방어선으로 잘못된 값을
 * 막는지 확인한다(나연 리뷰 반영).
 */
class ChallengeTest {

    // 유효한 값으로 채운 빌더 — 각 테스트가 한 필드만 잘못된 값으로 덮어써서 그 검사만 격리해 확인한다.
    private static Challenge.ChallengeBuilder validBuilder() {
        return Challenge.builder()
                .userId(1L)
                .durationDays(14)
                .startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(280000)
                .dailyLimit(20000);
    }

    @Test
    @DisplayName("유효한 값으로 생성하면 종료일(시작일 포함 기간의 마지막 날)과 시작 상태(진행 중)가 채워진다")
    void build_computesEndDateAndStartsInProgress() {
        Challenge challenge = validBuilder().build();

        assertThat(challenge.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(challenge.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 14)); // 6/1 + (14일 - 1)
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("유저 식별자를 빠뜨리면(null) 생성자 검사가 IllegalArgumentException으로 막는다")
    void build_rejectsNullUserId() {
        assertThatThrownBy(() -> validBuilder().userId(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시작일을 빠뜨리면(null) 종료일 계산 전에 생성자 검사가 IllegalArgumentException으로 막는다")
    void build_rejectsNullStartDate() {
        assertThatThrownBy(() -> validBuilder().startDate(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기간이 1일 미만이면 생성자 검사가 IllegalArgumentException으로 막는다")
    void build_rejectsDurationBelowOne() {
        assertThatThrownBy(() -> validBuilder().durationDays(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예산이 음수면 생성자 검사가 IllegalArgumentException으로 막는다")
    void build_rejectsNegativeBudget() {
        assertThatThrownBy(() -> validBuilder().budgetTotal(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예산 0원은 생성을 막지 않는다 — 구조적으로 문제없고(하루 한도 0으로 계산됨) 최소 예산 하한은 확정된 규칙이 없어 엔티티가 단정하지 않는다")
    void build_allowsZeroBudget() {
        Challenge challenge = validBuilder().budgetTotal(0).build();

        assertThat(challenge.getBudgetTotal()).isZero();
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("하루 한도가 음수면 생성자 검사가 IllegalArgumentException으로 막는다")
    void build_rejectsNegativeDailyLimit() {
        assertThatThrownBy(() -> validBuilder().dailyLimit(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("집중 카테고리를 교체하면 챌린지가 갖고 있던 카테고리는 사라지고, 넘긴 카테고리만 넘긴 순서대로 남는다")
    void replaceWeakCategories_replacesAllExisting() {
        Challenge challenge = validBuilder().build();
        challenge.replaceWeakCategories(List.of("배달", "카페"));

        challenge.replaceWeakCategories(List.of("카페", "편의점"));

        assertThat(challenge.getWeakCategories())
                .extracting(ChallengeWeakCategory::getCategory)
                .containsExactly("카페", "편의점"); // 배달은 빠졌고, 겹치는 카페는 남는다
    }

    @Test
    @DisplayName("같은 카테고리를 여러 번 보내도 한 개로 접혀 저장된다 — 한 챌린지에 같은 카테고리 두 줄은 저장할 수 없기 때문")
    void replaceWeakCategories_dedupes() {
        Challenge challenge = validBuilder().build();

        challenge.replaceWeakCategories(List.of("카페", "카페", "배달"));

        assertThat(challenge.getWeakCategories())
                .extracting(ChallengeWeakCategory::getCategory)
                .containsExactly("카페", "배달");
    }

    @Test
    @DisplayName("집중 카테고리를 하나도 없는 목록으로 교체하면 챌린지가 갖고 있던 카테고리가 전부 사라진다")
    void replaceWeakCategories_clearsWhenEmpty() {
        Challenge challenge = validBuilder().build();
        challenge.replaceWeakCategories(List.of("배달", "카페"));

        challenge.replaceWeakCategories(List.of());

        assertThat(challenge.getWeakCategories()).isEmpty();
    }

    @Test
    @DisplayName("3일 연속 지출 미입력으로 자동 취소하면 VOID 상태와 종료 사유가 함께 남고 다시 취소할 수 없다")
    void cancelForMissingInput_setsVoidWithReason() {
        Challenge challenge = validBuilder().build();
        LocalDate inactiveFrom = LocalDate.of(2026, 6, 4);

        challenge.cancelForMissingInput(inactiveFrom);

        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.VOID);
        assertThat(challenge.getEndReason()).isEqualTo(EndReason.MISSING_DAILY_INPUT);
        assertThat(challenge.getInactiveFrom()).isEqualTo(inactiveFrom);
        assertThatThrownBy(() -> challenge.cancelForMissingInput(inactiveFrom))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("결과가 확정된 챌린지를 최종 종료하면 종료 시각만 남고 성패(SUCCESS)는 그대로다")
    void close_recordsTimeWithoutChangingStatus() {
        Challenge challenge = validBuilder().build();
        challenge.applyResult(ChallengeStatus.SUCCESS);

        challenge.lockExpenseChanges(LocalDateTime.of(2026, 6, 15, 9, 30));

        assertThat(challenge.isExpenseLocked()).isTrue();
        assertThat(challenge.getExpenseLockedAt()).isEqualTo(LocalDateTime.of(2026, 6, 15, 9, 30));
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.SUCCESS);
    }

    @Test
    @DisplayName("진행 중인 챌린지를 최종 종료하려 하면 IllegalStateException으로 막는다 — 기간이 안 끝난 요청은 서비스가 409로 먼저 거르므로, 여기까지 왔다면 그 검사를 건너뛰고 호출한 서버 코드 실수라는 뜻")
    void close_rejectsInProgress() {
        Challenge challenge = validBuilder().build();

        assertThatThrownBy(() -> challenge.lockExpenseChanges(LocalDateTime.of(2026, 6, 15, 9, 30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 최종 종료한 챌린지를 다시 종료하려 하면 IllegalStateException으로 막아 첫 종료 시각을 지킨다")
    void close_rejectsSecondClose() {
        Challenge challenge = validBuilder().build();
        challenge.applyResult(ChallengeStatus.SUCCESS);
        challenge.lockExpenseChanges(LocalDateTime.of(2026, 6, 15, 9, 30));

        assertThatThrownBy(() -> challenge.lockExpenseChanges(LocalDateTime.of(2026, 6, 16, 9, 30)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(challenge.getExpenseLockedAt()).isEqualTo(LocalDateTime.of(2026, 6, 15, 9, 30));
    }
}
