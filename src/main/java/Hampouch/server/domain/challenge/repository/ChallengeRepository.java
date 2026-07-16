package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    /**
     * 동시 진행 1개 가정 — 생성 시 중복 체크용.
     * status(IN_PROGRESS)까지 보는 이유: 유저의 끝난 챌린지(SUCCESS/FAIL)도 DB에 남으므로,
     * userId만 보면 과거에 챌린지 한 유저는 새로 못 만듦. "진행 중"만 세야 함.
     */
    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    /** 진행 중 챌린지 1건 조회 (홈/현황). */
    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);
}
