package Hampouch.server.domain.rest.repository;

import Hampouch.server.domain.rest.entity.UserRest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface UserRestRepository extends JpaRepository<UserRest, Long> {

    /**
     * 기준일에 활성인 휴식 1건 — UserRest.isActiveOn과 같은 규칙의 쿼리판
     * (복귀 기록이 없거나, 복귀일이 기준일보다 뒤인 "내일 복귀 예약" 상태까지 활성으로 취급).
     *
     * 파생 쿼리(메서드 이름 문법)가 아니라 @Query(JPQL 직접 작성)인 이유: 이름 문법의 Or는
     * 조건 전체를 절 단위로 가르기만 해서 "userId는 공통이고 날짜 조건만 OR"라는 괄호 묶음
     * (userId = ? AND (actual IS NULL OR actual > ?))을 표현할 수 없다 — 이름으로 쓰면
     * userId 조건이 앞쪽 절에만 걸리는 다른 쿼리가 된다.
     *
     * "0 또는 1건" 계약은 두 다리로 선다. ① 데이터: 활성 휴식이 있으면 시작이 409로 막혀(UserRestService)
     * 유저당 활성 휴식은 최대 1건. ② 호출: 기준일에는 오늘만 넘긴다(파라미터 이름이 today인 이유) —
     * 과거 날짜를 넘기면 이미 닫힌 옛 휴식도 복귀일이 기준일보다 뒤라서 같이 매치돼, 정상 데이터로도
     * 2건이 걸릴 수 있다(과거 조회가 필요해지면 이 쿼리 재사용 말고 List 반환 쿼리를 새로 팔 것).
     * 계약이 깨져 2건 이상이 걸리면 IncorrectResultSizeDataAccessException(500)으로 드러난다
     * (ChallengeRepository.findInProgress와 같은 계약).
     */
    @Query("""
            select r from UserRest r
            where r.userId = :userId
              and (r.actualResumeDate is null or r.actualResumeDate > :today)
            """)
    Optional<UserRest> findActiveOn(@Param("userId") Long userId, @Param("today") LocalDate today);
}
