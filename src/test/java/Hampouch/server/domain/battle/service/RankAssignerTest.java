package Hampouch.server.domain.battle.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RankAssigner.assign()의 경쟁 순위 계산만 단독 검증 — Repository/Entity 의존이 없는 순수 함수라
 * Mockito/Spring 없이 바로 테스트한다.
 */
class RankAssignerTest {

    private record Item(String name, int amount) {
    }

    @Test
    @DisplayName("빈 리스트를 넣으면 빈 리스트를 반환한다")
    void assign_returnsEmptyForEmptyInput() {
        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(List.of(), Item::amount);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("단일 아이템은 무조건 1등이다")
    void assign_singleItemIsRankOne() {
        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(List.of(new Item("solo", 500)), Item::amount);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("동점자가 없으면 금액 오름차순으로 1,2,3등을 매기고, 반환 순서도 등수 오름차순이다")
    void assign_assignsSequentialRanksWithoutTies() {
        List<Item> items = List.of(new Item("c", 300), new Item("a", 100), new Item("b", 200));

        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(items, Item::amount);

        assertThat(result).extracting(r -> r.item().name()).containsExactly("a", "b", "c");
        assertThat(result).extracting(RankAssigner.Ranked::rank).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("동점자는 같은 등수를 공유하고, 다음 등수는 동점자 수만큼 건너뛴다 (경쟁 순위 1,1,3,4)")
    void assign_skipsRanksAfterTies() {
        List<Item> items = List.of(
                new Item("a", 1000), new Item("b", 1000), new Item("c", 2000), new Item("d", 3000));

        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(items, Item::amount);

        assertThat(result).extracting(RankAssigner.Ranked::rank).containsExactly(1, 1, 3, 4);
    }

    @Test
    @DisplayName("연속된 두 그룹이 동점이면 (1,1,3,3)처럼 각각 건너뛴다")
    void assign_handlesMultipleTieGroups() {
        List<Item> items = List.of(
                new Item("a", 100), new Item("b", 100), new Item("c", 200), new Item("d", 200));

        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(items, Item::amount);

        assertThat(result).extracting(RankAssigner.Ranked::rank).containsExactly(1, 1, 3, 3);
    }

    @Test
    @DisplayName("전원 동점이면 전부 1등이다 (READY 배틀 — 전원 0원 케이스와 동일 시나리오)")
    void assign_allTiedGetRankOne() {
        List<Item> items = List.of(new Item("a", 0), new Item("b", 0), new Item("c", 0));

        List<RankAssigner.Ranked<Item>> result = RankAssigner.assign(items, Item::amount);

        assertThat(result).extracting(RankAssigner.Ranked::rank).containsExactly(1, 1, 1);
    }
}
