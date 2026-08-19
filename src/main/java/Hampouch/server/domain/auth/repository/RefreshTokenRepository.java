package Hampouch.server.domain.auth.repository;

import Hampouch.server.domain.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 재발급(reissueToken) 전용: 같은 refresh token으로 동시에 두 요청이 들어오면 (revoked 여부 조회 -> revoke() -> 커밋) 사이에 경쟁 상태가 생겨 둘 다 통과할 수 있었음.
    // PESSIMISTIC_WRITE로 조회 시점에 row 락을 걸어, 먼저 조회한 트랜잭션이 끝날 때까지 뒤의 트랜잭션이 대기하게 만들어 한쪽만 성공하도록 보장
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    void revokeAllByUserId(@Param("userId") Long userId);
}
