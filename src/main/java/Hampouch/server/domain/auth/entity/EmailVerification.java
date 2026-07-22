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
@Table(name = "email_verifications")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(name = "verification_code", nullable = false, length = 10)
    private String verificationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationPurpose purpose;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(nullable = false)
    private int attemptCount = 0;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private EmailVerification(
            String email,
            String verificationCode,
            VerificationPurpose purpose,
            LocalDateTime expiredAt
    ) {
        this.email = email;
        this.verificationCode = verificationCode;
        this.purpose = purpose;
        this.expiredAt = expiredAt;
    }

    public static EmailVerification create(
            String email,
            String verificationCode,
            VerificationPurpose purpose,
            LocalDateTime expiredAt
    ) {
        return new EmailVerification(email, verificationCode, purpose, expiredAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(this.expiredAt);
    }

    public boolean isCodeMatch(String code) {
        return this.verificationCode.equals(code);
    }

    public void verify(LocalDateTime now) {
        this.verified = true;
        this.verifiedAt = now;
    }

    public boolean isVerificationExpired(LocalDateTime now) {
        return verifiedAt == null || verifiedAt.plusHours(1).isBefore(now);
    }

    public void increaseAttempt() {
        this.attemptCount++;
    }

    public boolean isAttemptExceeded() {
        return attemptCount >= 5;
    }
}