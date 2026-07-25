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
 * category=ETC를 고른 사용자가 직접 입력한 커스텀 카테고리 태그(유저별로 관리, 재사용 가능).
 * find-or-create 패턴 전제 — 같은 이름을 또 입력하면 새로 만들지 않고 기존 행을 재사용해야 하므로,
 * (user_id, name) 유니크 제약이 단순 중복 방지가 아니라 동시 요청 상황에서의 실제 find-or-create 가드 역할을 함
 * (애플리케이션 레벨 조회-후-생성만으로는 동시성 하에서 중복 생성을 막을 수 없음).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자, protected로 막아 of()를 통한 생성만 허용(Expense와 동일 컨벤션)
@Table(
        name = "custom_category",
        uniqueConstraints = @UniqueConstraint(name = "uq_custom_category_user_name", columnNames = {"user_id", "name"})
)
@EntityListeners(AuditingEntityListener.class)
public class CustomCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "custom_category_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 커스텀 태그는 반드시 특정 유저 소유 — 전역 공유 태그가 아님
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 50)
    private String name;

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at") // updatable=false로 수정 자체를 막음 — 재사용되는 태그의 생성 시점 기록이라 변경 이력 관리 대상이 아님
    private LocalDateTime createdAt;

    private CustomCategory(User user, String name) {
        this.user = user;
        this.name = name;
    }

    /** find-or-create 진입점. 서비스 계층에서 (user, name)으로 먼저 조회하고 없을 때만 이 팩토리를 호출하는 흐름을 전제로 함. */
    public static CustomCategory of(User user, String name) {
        return new CustomCategory(user, name);
    }
}
