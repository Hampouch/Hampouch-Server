package Hampouch.server.domain.battle.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 배틀 랭킹 계산 클래스 — Repository/Entity에 의존하지 않아 BattleService.getBattleDetail(),
 * toSummary()의 TERMINATED 분기 양쪽에서 재사용.
 */
public class RankAssigner {

    private RankAssigner() {
    }

    public record Ranked<T>(T item, int rank) {
    }

    /**
     * amountExtractor로 뽑은 값이 작을수록(사용 금액이 적을수록) 높은 등수.
     * 반환 리스트는 입력 순서가 아니라 등수 오름차순으로 정렬
     */
    public static <T> List<Ranked<T>> assign(List<T> items, ToLongFunction<T> amountExtractor) {
        List<T> sorted = items.stream()
                .sorted(Comparator.comparingLong(amountExtractor))
                .toList();

        List<Ranked<T>> result = new ArrayList<>(sorted.size());
        int rank = 0;
        long previousAmount = 0;
        for (int i = 0; i < sorted.size(); i++) {
            T item = sorted.get(i);
            long amount = amountExtractor.applyAsLong(item);
            if (i == 0 || amount != previousAmount) {
                rank = i + 1;
            }
            result.add(new Ranked<>(item, rank));
            previousAmount = amount;
        }
        return result;
    }
}
