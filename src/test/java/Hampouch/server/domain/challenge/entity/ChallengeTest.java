package Hampouch.server.domain.challenge.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Challenge 엔티티의 생성 불변식 검증. 빌더는 필수 필드를 빠뜨려도 build()가 컴파일되므로(누락 시
 * 참조형 null·정수형 0), 생성자가 마지막 방어선으로 잘못된 값을 막는지 확인한다(나연 리뷰 반영).
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
    void 유효한_값이면_종료일과_시작상태가_채워진다() {
        Challenge challenge = validBuilder().build();

        assertThat(challenge.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(challenge.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 14)); // 6/1 + (14일 - 1)
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("유저 식별자를 빠뜨리면(null) 생성자 검사가 IllegalArgumentException으로 막는다")
    void 유저식별자가_null이면_생성이_막힌다() {
        assertThatThrownBy(() -> validBuilder().userId(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시작일을 빠뜨리면(null) 종료일 계산 전에 생성자 검사가 IllegalArgumentException으로 막는다")
    void 시작일이_null이면_생성이_막힌다() {
        assertThatThrownBy(() -> validBuilder().startDate(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기간이 1일 미만이면 생성자 검사가 IllegalArgumentException으로 막는다")
    void 기간이_1미만이면_생성이_막힌다() {
        assertThatThrownBy(() -> validBuilder().durationDays(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예산이 음수면 생성자 검사가 IllegalArgumentException으로 막는다")
    void 예산이_음수면_생성이_막힌다() {
        assertThatThrownBy(() -> validBuilder().budgetTotal(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예산 0원은 생성을 막지 않는다 — 구조적으로 문제없고(하루 한도 0으로 계산됨) 최소 예산 하한은 확정된 규칙이 없어 엔티티가 단정하지 않는다")
    void 예산_0원은_허용된다() {
        Challenge challenge = validBuilder().budgetTotal(0).build();

        assertThat(challenge.getBudgetTotal()).isZero();
        assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("하루 한도가 음수면 생성자 검사가 IllegalArgumentException으로 막는다")
    void 하루한도가_음수면_생성이_막힌다() {
        assertThatThrownBy(() -> validBuilder().dailyLimit(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
