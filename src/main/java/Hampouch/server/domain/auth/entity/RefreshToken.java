package Hampouch.server.domain.auth.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = {
                //애플리케이션 버그나 해시 충돌로 같은 해시가 중복 저장되는 걸 DB 레벨에서 방지
                //이름을 uk_refresh_token_hash로 고정 -> V2 마이그레이션에서 동일한 이름으로 생성한다.
                @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash")
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // refresh token 원문이 아니라 SHA-256 해시값 (DB 유출 시에도 원문 토큰이 노출되지 않도록)
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private RefreshToken(
            Long userId,
            String tokenHash,
            LocalDateTime expiredAt
    ) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiredAt = expiredAt;
    }

    public static RefreshToken create(
            Long userId,
            String tokenHash,
            LocalDateTime expiredAt
    ) {
        return new RefreshToken(userId, tokenHash, expiredAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(this.expiredAt);
    }

    public boolean isValid(LocalDateTime now) {
        return !this.revoked && !isExpired(now);
    }

    public void revoke() {
        this.revoked = true;
    }
}