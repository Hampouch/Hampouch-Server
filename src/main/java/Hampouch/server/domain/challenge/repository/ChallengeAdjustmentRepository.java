package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.ChallengeAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChallengeAdjustmentRepository extends JpaRepository<ChallengeAdjustment, Long> {

    /** 조정 가능 횟수 게이트용 — 행 수가 곧 사용 횟수다. */
    int countByChallenge_Id(Long challengeId);

    /** 타임라인 복원용. 같은 날 두 번 조정하면 effectiveDate가 같아지므로 id로 한 번 더 갈라 나중 것이 뒤에 오게 한다. */
    List<ChallengeAdjustment> findByChallenge_IdOrderByEffectiveDateAscIdAsc(Long challengeId);

    /** 히스토리처럼 챌린지 여러 건의 타임라인이 한꺼번에 필요할 때 — 건별 조회는 N+1이 된다. */
    List<ChallengeAdjustment> findByChallenge_IdInOrderByEffectiveDateAscIdAsc(List<Long> challengeIds);
}
