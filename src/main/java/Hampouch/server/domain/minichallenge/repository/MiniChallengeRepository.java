package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MiniChallengeRepository extends JpaRepository<MiniChallenge, Long> {

    /**
     * 유저의 미니 전체 조회 — 그날(§1) 응답의 기초 데이터.
     * "그날 활성"만 골라 오지 않고 전체를 가져오는 이유: 유저 스트릭(streakDays)은 과거 날짜를
     * 거슬러 가며 "그날 활성이던" 미니를 봐야 해서, 이미 끝난 미니도 계산에 필요하다.
     * 미니는 기간이 최대 31일이라 유저당 행 수가 작아 전체 조회 부담이 없다.
     */
    List<MiniChallenge> findByUserId(Long userId);

    /** 같은 미니의 체크·해제를 직렬화해 PUT 멱등성을 동시 요청에서도 유지한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MiniChallenge m WHERE m.id = :id")
    Optional<MiniChallenge> findByIdForUpdate(@Param("id") Long id);
}
