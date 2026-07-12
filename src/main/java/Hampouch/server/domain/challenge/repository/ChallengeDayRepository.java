package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.ChallengeDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChallengeDayRepository extends JpaRepository<ChallengeDay, Long> {

    /** upsert용 — 같은 날 기존 행 조회. (challenge.id 로 탐색) */
    Optional<ChallengeDay> findByChallenge_IdAndDayDate(Long challengeId, LocalDate dayDate);

    /** 결과/현황 집계용 — 챌린지의 모든 일자. */
    List<ChallengeDay> findByChallenge_Id(Long challengeId);

    /** 캘린더용 — 기간 내 일자. */
    List<ChallengeDay> findByChallenge_IdAndDayDateBetween(Long challengeId, LocalDate start, LocalDate end);
}
