package Hampouch.server.domain.expense.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지출 1건의 부가 정보. memo/이미지가 하나도 없는 지출이 더 많을 걸 감안해, Expense가 생성될 때 무조건 같이 만들지 않고
 * 실제로 memo나 이미지가 들어올 때만 생성되는 진짜 optional 1:1 관계로 둔다
 * (그래서 조회는 항상 findByExpenseId(Optional)를 거친다 — ExpenseDetailRepository 참고).
 * PK를 expense_id와 공유한 이유: 지출 1건당 상세 최대 1건 제약을 직접적으로 강제하기 위함.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "expense_detail")
public class ExpenseDetail {

    @Id
    @Column(name = "expense_id")
    private Long expenseId; // PK이자 FK — @MapsId로 Expense.id와 항상 동일하게 유지됨(JPA가 자동 동기화)

    /**
     * optional = false: expense_detail은 expense 없이 존재할 수 없는 자식 — Expense 쪽에는 역참조 필드를 두지 않는다
     * (Expense는 목록/분석 API에서 훨씬 자주 조회되는데, 거기에 지연로딩이라도 참조를 얹으면 실수로 N+1을
     * 유발하기 쉬워 의존 방향을 ExpenseDetail → Expense 단방향으로만 유지).
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "expense_id")
    private Expense expense;

    @Column(length = 300)
    private String memo;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    private ExpenseDetail(Expense expense, String memo) {
        this.expense = expense;
        this.memo = memo;
    }

    /**
     * 정적 팩토리 하나만 공개 통로로 둔 이유: memo만 받고 이미지 필드는 항상 null로 시작한다는 계약 명시
     * 이미지는 attachImage()로만 채워짐, PATCH .../photos 전용 경로.
     * 호출부(ExpenseService/ExpensePhotoService)가 memo/imageKey 둘 다 없을 때는 이 팩토리 자체를
     * 호출하지 않을 책임을 진다
     */
    public static ExpenseDetail of(Expense expense, String memo) {
        return new ExpenseDetail(expense, memo);
    }

    public void updateMemo(String memo) {
        this.memo = memo;
    }

    public void attachImage(String imageKey, String imageUrl) {
        this.imageKey = imageKey;
        this.imageUrl = imageUrl;
    }

    /** DELETE /expenses/{expenseId}/photos — imageKey/imageUrl만 비우고 memo는 손대지 않는다. */
    public void removeImage() {
        this.imageKey = null;
        this.imageUrl = null;
    }
}
