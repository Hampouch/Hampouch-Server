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

/** 지출 1건. name은 null 허용, category/emotion은 건너뛰면 ETC로 흡수(컬럼은 NOT NULL 유지). */
@Getter // 변경은 아래 도메인 메서드(assignCustomCategory 등)로만 허용
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 조회가 항상 (유저, 날짜, 상태) 조합이라 복합 인덱스 필요 — FK 인덱스(user_id)만으론 전체 스캔됨
@Table(name = "expense",
        indexes = @Index(name = "idx_expense_user_date_status", columnList = "user_id, expense_date, status"))
@EntityListeners(AuditingEntityListener.class) // 저장 직전 @CreatedDate/@LastModifiedDate를 자동 채움
public class Expense {

    /** 지출 1건 요청값의 상한. */
    public static final int PRICE_MAX = 10_000_000;

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
    private ExpenseStatus status; // soft delete 플래그(명시적 필드, @SQLDelete 안 씀)

    @Column(nullable = false, name = "expense_date")
    private LocalDate expenseDate; // date는 MySQL 예약어라 컬럼명은 expense_date, DTO는 date로 노출

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
        // null이면 ETC로 흡수(컬럼은 NOT NULL 유지)
        this.category = category != null ? category : ExpenseCategory.ETC;
        this.emotion = emotion != null ? emotion : ExpenseEmotion.ETC;
        this.expenseDate = expenseDate;
        this.user = user;
        this.status = ExpenseStatus.ACTIVE; // 생성 시점엔 항상 ACTIVE
    }

    /** 정적 팩토리 — status=ACTIVE 강제, custom*는 항상 null로 시작. */
    public static Expense of(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate, User user) {
        return new Expense(name, price, category, emotion, expenseDate, user);
    }

    /** category≠ETC면 customCategory를 가질 수 없음(DTO에서 이미 검증됨 — 여기선 방어적 체크). */
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

    /** 소유권 검증(EXPENSE_FORBIDDEN 판단용). */
    public boolean isOwnedBy(Long userId) {
        return this.user.getId().equals(userId);
    }

    /** PUT /expenses/{expenseId} — user/status/createdAt은 유지, custom*는 별도 메서드 책임. */
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
