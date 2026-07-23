package Hampouch.server.domain.expense.entity;

import Hampouch.server.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * emotion=ETC를 고른 사용자가 직접 입력한 커스텀 감정 태그(유저별로 관리, 재사용 가능).
 * CustomCategory와 완전히 동일한 find-or-create 전제 — (user_id, name) 유니크 제약이
 * 동시 요청 상황에서의 중복 생성을 막는 실질적 가드 역할을 함.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자, protected로 막아 of()를 통한 생성만 허용
@Table(
        name = "custom_emotion",
        uniqueConstraints = @UniqueConstraint(name = "uq_custom_emotion_user_name", columnNames = {"user_id", "name"})
)
@EntityListeners(AuditingEntityListener.class)
public class CustomEmotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "custom_emotion_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 커스텀 태그는 반드시 특정 유저 소유 — 전역 공유 태그가 아님
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 50)
    private String name;

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at") // updatable=false로 수정 자체를 막음 — 재사용되는 태그의 생성 시점 기록이라 변경 이력 관리 대상이 아님
    private LocalDateTime createdAt;

    private CustomEmotion(User user, String name) {
        this.user = user;
        this.name = name;
    }

    /** find-or-create 진입점. 서비스 계층에서 (user, name)으로 먼저 조회하고 없을 때만 이 팩토리를 호출하는 흐름을 전제로 함. */
    public static CustomEmotion of(User user, String name) {
        return new CustomEmotion(user, name);
    }
}
