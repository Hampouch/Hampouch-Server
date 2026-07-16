package Hampouch.server.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"})
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(nullable = false, unique = true, length = 30)
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

    @Column(name = "last_updated", nullable = false)
    private LocalDate lastUpdated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private User(
            String email,
            String password,
            String nickname,
            String profileImageUrl,
            AuthProvider provider,
            String providerId
    ) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.provider = provider;
        this.providerId = providerId;
        this.lastUpdated = LocalDate.now();
    }

    public static User createLocalUser(String email, String encodedPassword, String nickname) {
        return User.builder()
                .email(email)
                .password(encodedPassword)
                .nickname(nickname)
                .profileImageUrl(null)
                .provider(AuthProvider.LOCAL)
                .providerId(null)
                .build();
    }

    public static User createSocialUser(
            String email,
            String nickname,
            String profileImageUrl,
            AuthProvider provider,
            String providerId
    ) {
        return User.builder()
                .email(email)
                .password(null)
                .nickname(nickname)
                .profileImageUrl(profileImageUrl)
                .provider(provider)
                .providerId(providerId)
                .build();
    }

    public boolean isLocalUser() {
        return this.provider == AuthProvider.LOCAL;
    }

    public boolean isSocialUser() {
        return this.provider == AuthProvider.GOOGLE || this.provider == AuthProvider.KAKAO;
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