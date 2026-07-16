package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/current 응답 — 진행 중 챌린지 + 현황 + 홈 소비상태.
 *
 * 블록 구분 기준 = 데이터의 성격(출처·변하는 주기):
 * challenge(생성 때 정해져 고정된 설정값, 엔티티 저장분) / progress(조회 시점마다 계산되는 누적 집계)
 * / consumption(오늘 하루치 상태) / warningCards(경고 신호) / adjustment(한도 조정 현황).
 */
public record CurrentChallengeResponse(
        ChallengeView challenge,
        Progress progress,
        Consumption consumption,
        List<WarningCard> warningCards,  // 홈에 띄울 경고 카드 목록 — 카드 코드 정의·발동 조건은 WarningCard enum이 단일 출처. JSON엔 이름 문자열 배열로 직렬화
        Adjustment adjustment
) {
    /** 응답의 challenge 부분 — 엔티티 Challenge를 그대로 노출하지 않고 화면에 보일 필드만 담은 표현(내부 필드 은닉·응답 형태를 엔티티 변경과 분리). */
    public record ChallengeView(
            Long id,
            int durationDays,
            LocalDate startDate,
            LocalDate endDate,
            int budgetTotal,
            int dailyLimit,
            ChallengeStatus status
    ) {
    }

    /**
     * 저장값이 아니라 ChallengeDay 집계(조회 시 계산).
     * 지나간 미입력일 = 0원 = 성공으로 채워 계산(0714 확정 — 결과 화면과 동일 규칙).
     * 단 오늘은 아직 하루가 안 끝나 기록이 있을 때만 포함 — 안 그러면 매일 아침 오늘이 미리 성공으로 집계되고,
     * 자정마다 미기록 오늘이 초과 연속을 끊어 경고 카드가 사라지는 문제가 생김. 즉 집계 구간 = 시작일~어제(오늘 기록 시 오늘까지).
     */
    public record Progress(
            int elapsedDays,      // 오늘이 챌린지 며칠째(시작일=1, 오늘 포함). 종료 후엔 종료일 기준으로 멈춤
            int remainingDays,    // 남은 일수(오늘 제외). elapsedDays + remainingDays = 전체 기간
            int successDays,
            int overDays,
            int currentStreak,
            int savedAmountSoFar  // 시작~오늘 누적 절약액 Σmax(0, 한도-지출). 결과 화면 최종 savedAmount와 구분해 "SoFar"
    ) {
    }

    /**
     * 홈 소비상태 — 하루 사용률 2축(2026-07-07 확정). usageRate = todaySpent / dailyLimit.
     * TODO(령준 지출 연동): todaySpent 출처 교체 — 연동 전엔 시드/POST /days 입력값.
     */
    public record Consumption(
            int todaySpent,
            int todayRemaining,  // = dailyLimit - todaySpent (파생값). 화면 표시용이라 계산 규칙을 서버 단일 출처로 두고 내려줌(클라 재계산 방지). 초과 시 음수
            int dailyLimit,
            double usageRate,
            ConsumptionCharacter character,
            AlertLevel alertLevel
    ) {
    }

    /** 한도 조정 사용/최대 횟수(챌린지당 최대 2회). */
    public record Adjustment(
            int usedCount,
            int maxCount
    ) {
    }
}
