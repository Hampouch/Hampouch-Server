package Hampouch.server.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"}),
                @UniqueConstraint(name = "uk_user_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_user_nickname", columnNames = "nickname")
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(length = 30)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_updated") // 가입 직후엔 ACTIVE 지출이 하나도 없으므로 null이 정확한 상태(nullable 허용)
    private LocalDate lastUpdated;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private User(String email, String password, String nickname, String profileImageUrl, AuthProvider provider, String providerId
    ) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.provider = provider;
        this.providerId = providerId;
        this.lastUpdated = null; // 첫 지출 입력 전까지 "기록 없음"을 의미 — ExpenseService.create()가 null이면 비교 없이 그대로 반영
    }

    public static User createLocalUser(String email, String encodedPassword, String nickname) {
        return new User(email, encodedPassword, nickname, null, AuthProvider.LOCAL, null);
    }

    public static User createSocialUser(String email, AuthProvider provider, String providerId
    ) {
        return new User(email, null, null, null, provider, providerId);
    }

    public boolean isLocalUser() {
        return this.provider == AuthProvider.LOCAL;
    }

    public boolean isSocialUser() {
        return this.provider == AuthProvider.GOOGLE || this.provider == AuthProvider.KAKAO;
    }

    public boolean hasNickname() {
        return this.nickname != null;
    }

    public void setInitialNickname(String nickname) {
        this.nickname = nickname;
    }

    public boolean isDeleted() {
        return this.status == UserStatus.DELETED;
    }

    public void resetPassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void delete() {
        this.status = UserStatus.DELETED;
    }

    public void updateLastUpdated(LocalDate date) {
        this.lastUpdated = date;
    }
}