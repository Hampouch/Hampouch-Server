package Hampouch.server.domain.challenge.service;

import java.time.LocalDate;

/**
 * Expense 도메인이 인사이트 문구를 고를 때 쓰는 진행 중 챌린지 조회 진입점
 * ChallengeService를 그대로 주입하지 않는 이유는 그쪽이 ExpenseSpendingQuery(구현체가 ExpenseAnalysisService)를
 * 이미 물고 있어, 반대 방향 의존을 더하면 두 서비스가 서로를 참조하는 순환 의존이 되기 때문이다.
 */
public interface ChallengeProgressQuery {

    /**
     * userId의 IN_PROGRESS 챌린지가 [periodStart, periodEnd]와 하루라도 겹치는지, 겹친다면 예산 페이스를 지키고 있는지
     * 겹침 여부만으로 문구를 고르면 예산을 이미 넘긴 사람에게도 "다음 챌린지에도 조금 줄여보라"는
     * 칭찬 섞인 제안이 나가므로, 지금 상황까지 함께 답한다.
     *
     * 오늘이 그 기간 안인지는 보지 않는다 — 지난달을 조회해도 진행 중 챌린지가 그 달에 걸쳐 있으면 겹친 것이다.
     * 호출자가 이미 유효한 기간만 넘긴다는 전제라 null / 기간 역전은 검증하지 않는다.
     */
    ChallengeProgress overlappingChallengeProgress(Long userId, LocalDate periodStart, LocalDate periodEnd);
}
