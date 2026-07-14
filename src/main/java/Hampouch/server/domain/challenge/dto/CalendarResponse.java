package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.DayStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/{id}/calendar 응답 — 기록 있는 날만(기간 내).
 * 경로의 {id} = 조회 대상 챌린지의 식별자(생성 응답의 challengeId, 현황 응답의 challenge.id와 같은 값).
 * 남의 챌린지 id면 403.
 * challengeId/year/month를 요청값 그대로 되돌려주는 이유: 응답만 봐도 무엇의 데이터인지 알 수 있게(로그·디버깅)
 * + 동시 요청 시 클라이언트가 어느 요청의 응답인지 판별할 수 있게.
 */
public record CalendarResponse(
        Long challengeId,
        int year,                // 조회한 달력의 연/월 (요청 쿼리 echo) — 챌린지 생성일·시작일과 무관
        int month,
        List<DayView> days       // 그 달 안에서 기록 있는 날만
) {
    /**
     * days 배열의 원소 = 하루치 기록. 캘린더 응답에서만 쓰는 전용 부품이라 중첩으로 소속을 명시(여러 곳에서 쓰면 별도 파일로).
     * View 접미사 = ChallengeDay 엔티티(저장 원본)를 화면에 필요한 필드만 추려 "바라본 모습"이라는 뜻
     * (ChallengeView와 같은 관례 — 원본이 아니라 조회 전용 투영임을 이름으로 표시).
     */
    public record DayView(
            LocalDate date,
            DayStatus status,
            int spentAmount
    ) {
    }
}
