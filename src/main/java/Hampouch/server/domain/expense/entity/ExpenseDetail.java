package Hampouch.server.domain.expense.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 지출 1건의 부가 정보(memo/이미지). 하나라도 있을 때만 생성되는 진짜 optional 1:1 — 조회는 항상 Optional.
 * PK를 expense_id와 공유해 지출당 상세 최대 1건을 강제한다.
 */
@Getter
@Entity
@DynamicUpdate // 바뀐 컬럼만 UPDATE
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "expense_detail")
public class ExpenseDetail {

    @Id
    @Column(name = "expense_id")
    private Long expenseId; // PK=FK, @MapsId로 Expense.id와 동기화

    /** Expense 쪽엔 역참조를 안 둔다 — 더 자주 조회되는 Expense에 N+1 위험을 얹지 않기 위해 단방향 유지. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "expense_id")
    private Expense expense;

    @Column(length = 300)
    private String memo;

    @Column(name = "image_key", length = 500)
    private String imageKey; // imageUrl은 저장하지 않는다 — 조회 시점마다 ExpenseImageService.presignGetUrl()로 새로 발급

    private ExpenseDetail(Expense expense, String memo) {
        this.expense = expense;
        this.memo = memo;
    }

    public static ExpenseDetail of(Expense expense, String memo) {
        return new ExpenseDetail(expense, memo);
    }

    public void updateMemo(String memo) {
        this.memo = memo;
    }

    public void attachImage(String imageKey) {
        this.imageKey = imageKey;
    }

    /** DELETE /expenses/{expenseId}/photos — imageKey만 비우고 memo는 손대지 않는다. */
    public void removeImage() {
        this.imageKey = null;
    }
}
