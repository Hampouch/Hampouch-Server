package Hampouch.server.domain.battle.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 배틀 랭킹 계산 전용 순수 함수 클래스 — Repository/Entity에 의존하지 않아 BattleService의
 * getBattleDetail()과 toSummary()의 TERMINATED 분기(winnerNickname) 양쪽에서 재사용하고,
 * RankAssignerTest로 리포지토리/DB 없이 독립 단위 테스트가 가능하다.
 * battle domain 특성상 total_amount가 작을수록 좋은 등수 — 오름차순 고정.
 * 동점자는 같은 등수를 공유하고 다음 등수는 그만큼 건너뛴다(경쟁 순위, 예: 1,1,3,4)
 */
public class RankAssigner {

    private RankAssigner() {
    }

    public record Ranked<T>(T item, int rank) {
    }

    /**
     * amountExtractor로 뽑은 값이 작을수록 높은 등수(1위)를 받는다. long을 쓰는 이유(PR #128 리뷰
     * 반영, 2026-08-11): 지출 합계는 DB에서 SUM()으로 집계되는 순간 JPQL 규약상 Long이 되므로,
     * 여기서 int로 좁히면 아주 큰 합계에서 조용히 오버플로가 날 수 있다.
     * 반환 리스트는 입력 순서가 아니라 등수 오름차순으로 정렬되어 있다 — 호출부가 곧바로
     * 응답 리스트 순서로 써도 되게 하기 위함(BattleDetailResponse.participants는 랭킹 순으로 노출).
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
