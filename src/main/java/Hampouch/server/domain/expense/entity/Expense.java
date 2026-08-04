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
 * 금액이 0원 이상인 지출 1건.
 * memo/사진 첨부는 이번 스코프 밖이라 expense_detail 관련 필드/엔티티는 여기 넣지 않음.
 */
@Getter // 필드별 getter만 생성, setter는 의도적으로 안 둠 — 변경은 아래 도메인 메서드(assignCustomCategory 등)로만 허용
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA가 프록시/영속 객체 복원에 빈 생성자를 요구 — protected로 막아 외부에서 new Expense()로 반쪽짜리 객체 생성 못 하게 하고 of()로만 생성
@Table(name = "expense")
@EntityListeners(AuditingEntityListener.class) // 저장 직전 @CreatedDate/@LastModifiedDate를 자동 채움 — 이 리스너 빠지면 두 필드가 계속 null로 남음(Challenge.java와 동일 컨벤션)
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // PK 발급은 DB(auto_increment) 책임 — Flyway 없이 ddl-auto:update로 스키마를 만들기 때문에 IDENTITY가 가장 단순
    @Column(name = "expense_id")
    private Long id;

    @Column(nullable = false, length = 90)
    private String name;

    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING) // ORDINAL 금지 — enum 값 순서가 바뀌거나 새 값이 중간에 추가되면 이미 저장된 데이터가 조용히 깨짐
    @Column(nullable = false)
    private ExpenseCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseEmotion emotion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status; // soft delete용 상태 플래그 — @SQLDelete/@Where 매직 대신 명시적 status 필드로 처리(ChallengeDay의 DayStatus 패턴과 동일)

    @Column(nullable = false, name = "expense_date")
    private LocalDate expenseDate; // 컬럼명을 expense_date로 명시한 이유: MySQL 예약어 date와 충돌 회피. DTO(JSON)에서는 date로 노출 — 엔티티 내부 명명과 API 명세 필드명은 별개

    @CreatedDate
    @Column(nullable = false, name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 지출은 반드시 특정 유저에 귀속 — optional=false(자바 레벨)+nullable=false(DB 레벨) 둘 다로 강제(CustomCategory/CustomEmotion과 동일 컨벤션)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_category_id") // category=ETC일 때만 채워지는 선택적 연관관계라 nullable 유지
    private CustomCategory customCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_emotion_id") // emotion=ETC일 때만 채워지는 선택적 연관관계라 nullable 유지
    private CustomEmotion customEmotion;

    private Expense(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate, User user) {
        this.name = name;
        this.price = price;
        this.category = category;
        this.emotion = emotion;
        this.expenseDate = expenseDate;
        this.user = user;
        this.status = ExpenseStatus.ACTIVE; // 생성 시점엔 항상 ACTIVE — DELETED로 시작하는 경로는 없어서 파라미터로 안 받고 팩토리에서 고정
    }

    /**
     * private 생성자 대신 정적 팩토리를 노출한 이유: status 기본값(ACTIVE) 강제, customCategory/customEmotion은
     * 생성 시점엔 항상 null(수정 API에서만 연결)이라는 계약을 이름으로 드러내기 위함.
     */
    public static Expense of(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate, User user) {
        return new Expense(name, price, category, emotion, expenseDate, user);
    }

    /**
     * 커스텀 카테고리 태그 연결/해제(서비스 계층에서 find-or-create된 CustomCategory를 넘기거나, null로 해제).
     * category=ETC ↔ customCategory 존재 여부 일관성은 DTO(@AssertTrue)에서 이미 검증됐다고 전제 — 여기선 그 전제가
     * 깨진 채로(=코드 버그로) 호출되는 경우만 즉시 잡아낸다(Challenge.applyResult()와 동일하게 IllegalArgumentException).
     */
    public void assignCustomCategory(CustomCategory customCategory) {
        if (category != ExpenseCategory.ETC && customCategory != null) {
            throw new IllegalArgumentException("category가 ETC가 아니면 customCategory를 연결할 수 없음: " + category);
        }
        this.customCategory = customCategory;
    }

    /** customCategory와 대칭 — emotion=ETC일 때만 연결 가능, 그 외 null로 해제. */
    public void assignCustomEmotion(CustomEmotion customEmotion) {
        if (emotion != ExpenseEmotion.ETC && customEmotion != null) {
            throw new IllegalArgumentException("emotion이 ETC가 아니면 customEmotion을 연결할 수 없음: " + emotion);
        }
        this.customEmotion = customEmotion;
    }

    /** 소유권 검증 — ChallengeService.loadOwned()가 Challenge.isOwnedBy()를 쓰는 것과 동일한 패턴. 서비스 계층에서 조회 직후 호출해 EXPENSE_FORBIDDEN 판단에 사용. */
    public boolean isOwnedBy(Long userId) {
        return this.user.getId().equals(userId);
    }

    /**
     * PUT /expenses/{expenseId} — user/status/createdAt은 손대지 않음(귀속·삭제상태·최초생성시각은 수정 대상 아님).
     * customCategory/customEmotion은 여기서 건드리지 않는다 — assignCustomCategory/assignCustomEmotion과 책임을 분리해
     * 생성 때와 동일한 경로(둘 중 하나 호출)로 ETC↔customXxx 일관성 검증을 재사용하기 위함. 즉 서비스 계층은
     * update() 호출 뒤 category/emotion이 바뀌었든 아니든 항상 assignCustomCategory/assignCustomEmotion을
     * 다시 호출해 customCategory/customEmotion을 새 상태에 맞게 재확정해야 한다(그렇지 않으면 예: ETC→DINING_OUT으로
     * 바꿨는데 customCategory FK가 그대로 남는 불일치가 생김).
     */
    public void update(String name, int price, ExpenseCategory category, ExpenseEmotion emotion, LocalDate expenseDate) {
        this.name = name;
        this.price = price;
        this.category = category;
        this.emotion = emotion;
        this.expenseDate = expenseDate;
    }

    /** DELETE /expenses/{expenseId} — 물리 삭제 대신 상태 플립(User.delete()와 동일 패턴). */
    public void delete() {
        this.status = ExpenseStatus.DELETED;
    }
}
