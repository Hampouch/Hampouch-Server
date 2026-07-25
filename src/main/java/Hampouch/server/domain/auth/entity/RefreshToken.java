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
@Table(name = "refresh_tokens")
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