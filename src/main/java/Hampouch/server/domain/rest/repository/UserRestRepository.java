package Hampouch.server.domain.rest.repository;

import Hampouch.server.domain.rest.entity.UserRest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface UserRestRepository extends JpaRepository<UserRest, Long> {

    /** 오늘의 휴식 상태 조회. */
    @Query("""
            select r from UserRest r
            where r.userId = :userId
              and (r.actualResumeDate is null or r.actualResumeDate > :today)
            """)
    Optional<UserRest> findActiveOn(@Param("userId") Long userId, @Param("today") LocalDate today);

}
