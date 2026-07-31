package Hampouch.server.domain.battle.entity;

import Hampouch.server.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BattleParticipant 생성 시 기본 상태와 무효화·결과 확정 동작 검증.
 * of()는 user/battle을 계산 없이 그대로 대입만 하므로(Battle.of()의 startDate처럼 계산 전제조건이
 * 아님) null 가드를 두지 않는다 — Expense.of()와 동일한 "포지셔널 팩토리는 값 검증 안 함" 원칙.
 */
class BattleParticipantTest {

    private static User user(Long id) {
        User user = User.createLocalUser("p" + id + "@hampouch.com", "encoded", "p" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Battle battle() {
        return Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(99L));
    }

    @Test
    @DisplayName("생성하면 isValid는 true로 시작하고 rank/totalAmount는 아직 비어있다")
    void of_startsValidWithoutResult() {
        BattleParticipant participant = BattleParticipant.of(user(1L), battle());

        assertThat(participant.isValid()).isTrue();
        assertThat(participant.getRank()).isNull();
        assertThat(participant.getTotalAmount()).isNull();
    }

    @Test
    @DisplayName("invalidate()를 호출하면 isValid가 false로 바뀐다")
    void invalidate_setsFalse() {
        BattleParticipant participant = BattleParticipant.of(user(1L), battle());

        participant.invalidate();

        assertThat(participant.isValid()).isFalse();
    }

    @Test
    @DisplayName("finalizeResult()를 호출하면 rank/totalAmount가 세팅된다")
    void finalizeResult_setsRankAndTotalAmount() {
        BattleParticipant participant = BattleParticipant.of(user(1L), battle());

        participant.finalizeResult(2, 15000);

        assertThat(participant.getRank()).isEqualTo(2);
        assertThat(participant.getTotalAmount()).isEqualTo(15000);
    }

    @Test
    @DisplayName("isOwnedBy는 참가자 본인의 id와 같을 때만 true를 반환한다")
    void isOwnedBy_matchesUserIdOnly() {
        BattleParticipant participant = BattleParticipant.of(user(1L), battle());

        assertThat(participant.isOwnedBy(1L)).isTrue();
        assertThat(participant.isOwnedBy(2L)).isFalse();
    }
}
