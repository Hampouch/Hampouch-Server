package Hampouch.server.domain.expense.entity;

import Hampouch.server.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 지출 1건(사용자가 직접 입력한 식비 지출 기록).
 * name은 null 허용. category/emotion은 건너뛰어도 컬럼 자체는 NOT NULL로 유지 및 ETC 흡수
 * ETC로 Enum을 설정해야 분석 집계가 null 케이스를 추가로 신경 쓸 필요가 없다.
 */
@Getter // 변경은 아래 도메인 메서드(assignCustomCategory 등)로만 허용
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "expense")
@EntityListeners(AuditingEntityListener.class) // 저장 직전 @CreatedDate/@LastModifiedDate를 자동 채움
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // PK 발급은 DB(auto_increment) 책임
    @Column(name = "expense_id")
    private Long id;

    @Column(length = 90) // 지출명 입력을 건너뛰면 null로 저장
    private String name;

    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseEmotion emotion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status; // soft delete용 상태 플래그 — @SQLDelete/@Where 매직 대신 명시적 status 필드로 처리

    @Column(nullable = false, name = "expense_date")
    private LocalDate expenseDate; // 컬럼명을 expense_date로 명시한 이유: MySQL 예약어 date와 충돌 회피. DTO(JSON)에서는 date로 노출

    @CreatedDate
    @Column(nullable = false, name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 지출은 반드시 특정 유저에 귀속
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "custom_category", length = 50) // category=ETC일 때만 채워지는 자유 입력 태그
    private String customCategory;

    @Column(name = "custom_emotion", length = 50)  // customCategory와 대칭 — emotion=ETC일 때만 채워지는 자유 입력 태그
    private String customEmotion;

    private Expense(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate, User user) {
        this.name = name;
        this.price = price;
        // 카테고리/이유를 건너뛰면 null이 아니라 ETC로 흡수한다. 컬럼은 계속 NOT NULL로 유지
        // 미분류를 나타내는 값을 ETC에 통합, 분석 집계 시 해당 지출도 통계 조회가 가능하도록.
        this.category = category != null ? category : ExpenseCategory.ETC;
        this.emotion = emotion != null ? emotion : ExpenseEmotion.ETC;
        this.expenseDate = expenseDate;
        this.user = user;
        this.status = ExpenseStatus.ACTIVE; // 생성 시점엔 항상 ACTIVE
    }

    /**
     * private 생성자 대신 정적 팩토리를 노출한 이유: status 기본값(ACTIVE) 강제, customCategory/customEmotion은
     * 생성 시점엔 항상 null이라는 계약을 이름으로 드러내기 위함.
     */
    public static Expense of(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate, User user) {
        return new Expense(name, price, category, emotion, expenseDate, user);
    }

    /**
     * 커스텀 카테고리 태그 기록/해제. category의 Enum value가 ETC가 아니면 커스텀 값을 가질 수 없다
     * category=ETC ↔ customCategory 존재 여부 일관성은 DTO(@AssertTrue)에서 이미 검증됐다고 전제 — 여기선 그 전제가
     * 깨진 채로(=코드 버그로) 호출되는 경우만 즉시 잡아낸다(IllegalArgumentException으로 catch).
     */
    public void assignCustomCategory(String customCategory) {
        if (category != ExpenseCategory.ETC && customCategory != null) {
            throw new IllegalArgumentException("category가 ETC가 아니면 customCategory를 기록할 수 없음: " + category);
        }
        this.customCategory = customCategory;
    }

    /** customCategory와 대칭 — emotion=ETC일 때만 기록 가능, 그 외 null로 해제. */
    public void assignCustomEmotion(String customEmotion) {
        if (emotion != ExpenseEmotion.ETC && customEmotion != null) {
            throw new IllegalArgumentException("emotion이 ETC가 아니면 customEmotion을 기록할 수 없음: " + emotion);
        }
        this.customEmotion = customEmotion;
    }

    /** 소유권 검증 — 서비스 계층에서 조회 직후 호출해 EXPENSE_FORBIDDEN 판단에 사용. */
    public boolean isOwnedBy(Long userId) {
        return this.user.getId().equals(userId);
    }

    /**
     * PUT /expenses/{expenseId} — user/status/createdAt은 손대지 않음(귀속·삭제상태·최초생성시각은 수정 대상 아님).
     * customCategory/customEmotion은 여기서 건드리지 않는다 — assignCustomCategory/assignCustomEmotion과 책임을 분리
     */
    public void update(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate) {
        this.name = name;
        this.price = price;
        this.category = category != null ? category : ExpenseCategory.ETC;
        this.emotion = emotion != null ? emotion : ExpenseEmotion.ETC;
        this.expenseDate = expenseDate;
    }

    /** DELETE /expenses/{expenseId} — 물리 삭제 대신 상태 플립(User.delete()와 동일 패턴). */
    public void delete() {
        this.status = ExpenseStatus.DELETED;
    }
}
