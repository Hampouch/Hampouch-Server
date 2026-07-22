package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.ChallengeDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 구현 코드가 없어도 Spring Data JPA가 메서드 이름을 문법처럼 해석해 쿼리를 만들어 준다(쿼리 메서드).
 * findBy 뒤에 조건 필드를 And 등으로 잇는 형태. 밑줄(_)은 연관 객체 안으로 들어가는 경로 구분자 —
 * Challenge_Id = ChallengeDay의 challenge 필드를 타고 그 안의 id (SQL: where challenge_id = ?).
 * 밑줄 없이 ChallengeId로 써도 대개 추론되지만, challengeId라는 단일 필드로 오해될 여지가 있어 경로를 명시했다.
 */
public interface ChallengeDayRepository extends JpaRepository<ChallengeDay, Long> {

    /**
     * 그 챌린지의 특정 날짜 기록 1건 조회 (challenge_id + day_date 유니크 제약이라 최대 1건 → Optional).
     * 쓰는 곳: upsertDay의 upsert(= update + insert 합성어) 분기 — 같은 날짜 기록이 이미 있으면
     * 그 행을 수정하고, 없으면 새 행을 저장하기 위해 "먼저 있는지" 확인하는 단계.
     * getCurrent의 오늘 지출 조회에도 쓴다.
     */
    Optional<ChallengeDay> findByChallenge_IdAndDayDate(Long challengeId, LocalDate dayDate);

    /** 결과/현황 집계용 — 챌린지의 모든 일자. */
    List<ChallengeDay> findByChallenge_Id(Long challengeId);

    /**
     * 캘린더용 — 기간 내 일자. 이름이 다음 JPQL로 파싱된다:
     * select d from ChallengeDay d where d.challenge.id = ?1 and d.dayDate between ?2 and ?3
     * between a and b는 "a 이상 그리고 b 이하"(양 끝 포함)의 줄임 표현 — dayDate >= ?2 and dayDate <= ?3 과 동일.
     */
    List<ChallengeDay> findByChallenge_IdAndDayDateBetween(Long challengeId, LocalDate start, LocalDate end);

    /**
     * 히스토리(#4) 집계용 — 여러 챌린지의 일자를 in절 한 번에 가져온다.
     * 챌린지마다 findByChallenge_Id를 반복 호출하면 챌린지 수만큼 쿼리가 나가는 N+1이 되므로
     * (미니 §1 조회의 loadCheckedDatesAsOf와 같은 이유·같은 패턴) 묶어서 1쿼리로.
     */
    List<ChallengeDay> findByChallenge_IdIn(Collection<Long> challengeIds);
}
