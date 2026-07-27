package Hampouch.server.domain.battle.entity;

/**
 * READY: 생성 직후, 시작일 전까지 참가자 모집 중 / ONGOING: 시작일 배치가 정원 충족 확인 후 전환
 * TERMINATED: 종료일 배치가 결과 스냅샷 확정 / CANCELLED: 시작일 배치가 정원 미달 확인 후 자동 취소
 */
public enum BattleStatus {
    READY,
    ONGOING,
    TERMINATED,
    CANCELLED
}
