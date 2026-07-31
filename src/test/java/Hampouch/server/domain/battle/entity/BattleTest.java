package Hampouch.server.domain.battle.entity;

import Hampouch.server.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Battle 엔티티의 생성 규칙과 상태 전이(READY→ONGOING→TERMINATED, READY→CANCELLED) 검증.
 * capacity/durationDays 범위 등 값 검증은 서비스 계층 책임이라(0727 결정, Battle.of() 참조) 여기선
 * startDate null 가드와 상태 전이 규칙만 확인한다 — of()는 포지셔널이라 필드 누락 자체가 불가능하다.
 */
class BattleTest {

    private static User creator(Long id) {
        User user = User.createLocalUser("creator" + id + "@hampouch.com", "encoded", "creator" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Battle validBattle() {
        return Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", creator(1L));
    }

    @Test
    @DisplayName("생성하면 종료일(시작일 포함 기간의 마지막 날)이 계산되고 상태는 READY로 시작한다")
    void of_computesEndDateAndStartsReady() {
        Battle battle = validBattle();

        assertThat(battle.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 7)); // 8/1 + (7일 - 1)
        assertThat(battle.getStatus()).isEqualTo(BattleStatus.READY);
        assertThat(battle.getPenaltyUser()).isNull(); // terminate() 전엔 항상 null
    }

    @Test
    @DisplayName("시작일이 null이면 종료일 계산 전에 IllegalArgumentException으로 막는다 — endDate 계산의 전제조건")
    void of_rejectsNullStartDate() {
        assertThatThrownBy(() -> Battle.of("ABCD1234", "짠테크 배틀", 4, 7, null, "치킨 사주기", creator(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("READY 상태에서 start()를 호출하면 ONGOING으로 바뀐다")
    void start_transitionsReadyToOngoing() {
        Battle battle = validBattle();

        battle.start();

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.ONGOING);
    }

    @Test
    @DisplayName("이미 시작된(ONGOING) 배틀은 다시 start()할 수 없다 — IllegalStateException")
    void start_rejectsWhenNotReady() {
        Battle battle = validBattle();
        battle.start();

        assertThatThrownBy(battle::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("READY 상태에서 cancel()을 호출하면 CANCELLED로 바뀐다")
    void cancel_transitionsReadyToCancelled() {
        Battle battle = validBattle();

        battle.cancel();

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 시작된(ONGOING) 배틀은 cancel()할 수 없다 — 자동취소는 시작 전(READY)에만 가능")
    void cancel_rejectsWhenNotReady() {
        Battle battle = validBattle();
        battle.start();

        assertThatThrownBy(battle::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ONGOING 상태에서 terminate()를 호출하면 TERMINATED로 바뀌고 penaltyUser가 세팅된다")
    void terminate_transitionsOngoingToTerminatedAndSetsPenaltyUser() {
        Battle battle = validBattle();
        battle.start();
        User penaltyUser = creator(2L);

        battle.terminate(penaltyUser);

        assertThat(battle.getStatus()).isEqualTo(BattleStatus.TERMINATED);
        assertThat(battle.getPenaltyUser()).isSameAs(penaltyUser);
    }

    @Test
    @DisplayName("시작 전(READY) 배틀은 terminate()할 수 없다 — 종료는 진행 중(ONGOING)에서만 가능")
    void terminate_rejectsWhenNotOngoing() {
        Battle battle = validBattle();

        assertThatThrownBy(() -> battle.terminate(creator(2L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("isOwnedBy는 creator의 id와 같을 때만 true를 반환한다")
    void isOwnedBy_matchesCreatorIdOnly() {
        Battle battle = validBattle();

        assertThat(battle.isOwnedBy(1L)).isTrue();
        assertThat(battle.isOwnedBy(2L)).isFalse();
    }
}
