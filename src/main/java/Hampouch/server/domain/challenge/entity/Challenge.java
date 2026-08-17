package Hampouch.server.domain.challenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "challenge",
        indexes = {
                @Index(name = "idx_challenge_user_date_lookup",
                        columnList = "user_id, start_date, id, end_date, inactive_from"),
                @Index(name = "idx_challenge_status_id", columnList = "status, id")
        },
        uniqueConstraints = @UniqueConstraint(name = Challenge.ACTIVE_USER_UNIQUE, columnNames = "active_user_id"))
@EntityListeners(AuditingEntityListener.class)
public class Challenge {

    public static final String ACTIVE_USER_UNIQUE = "uq_challenge_active_user";

    /** 목표 금액 요청값의 상한(원). 목표 조정 후 저장값은 이 상한을 넘을 수 있다. */
    public static final int BUDGET_TOTAL_MAX = 10_000_000;
    public static final int AUTO_CANCEL_MIN_DURATION_DAYS = 8;
    public static final int MISSING_INPUT_CANCEL_DAYS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int durationDays;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private int budgetTotal;

    /** 조정 시 갱신되는 현재 한도. 지난 날짜의 판정 기준은 ChallengeDay의 스냅샷이 보존한다. */
    @Column(nullable = false)
    private int dailyLimit;

    @Column(name = "fixed_day")
    private Integer fixedDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeStatus status;

    /**
     * 진행 중일 때만 userId가 들어가는 생성 컬럼이다. 유니크 제약과 함께 동시 생성도 한 건으로 제한한다.
     * H2와 MySQL이 모두 허용하도록 STORED/VIRTUAL은 생략하고 읽기 전용으로 매핑한다.
     */
    @Column(name = "active_user_id", insertable = false, updatable = false,
            columnDefinition = "bigint generated always as (case when status = 'IN_PROGRESS' then user_id end)")
    private Long activeUserId;

    /** 포기·미입력 취소 사유. 지출 기록으로 계산한 결과에는 값이 없다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EndReason endReason;

    /** 포기·자동 취소가 적용된 날짜. 이 날짜부터 해당 챌린지를 날짜 조회에서 제외한다. */
    @Column(name = "inactive_from")
    private LocalDate inactiveFrom;

    /** 해당 챌린지 기간의 지출 변경을 잠근 시각. */
    @Column(name = "expense_locked_at")
    private LocalDateTime expenseLockedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 필수값 검증과 파생값 초기화를 거치도록 생성자에만 빌더를 노출한다. */
    @Builder
    private Challenge(Long userId, int durationDays, LocalDate startDate, int budgetTotal,
                      int dailyLimit, Integer fixedDay) {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("startDate는 필수입니다.");
        }
        if (durationDays < 1) {
            throw new IllegalArgumentException("durationDays는 1 이상이어야 합니다: " + durationDays);
        }
        if (budgetTotal < 0) {
            throw new IllegalArgumentException("budgetTotal은 0 이상이어야 합니다: " + budgetTotal);
        }
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("dailyLimit은 0 이상이어야 합니다: " + dailyLimit);
        }
        if (fixedDay != null && (fixedDay < 1 || fixedDay > 31)) {
            throw new IllegalArgumentException("fixedDay는 1 이상 31 이하여야 합니다: " + fixedDay);
        }
        this.userId = userId;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = startDate.plusDays(durationDays - 1L);
        this.budgetTotal = budgetTotal;
        this.dailyLimit = dailyLimit;
        this.fixedDay = fixedDay;
        this.status = ChallengeStatus.IN_PROGRESS;
    }

    /** 지출 기록으로 계산한 SUCCESS/FAIL 결과를 반영한다. */
    public void applyResult(ChallengeStatus result) {
        if (result != ChallengeStatus.SUCCESS && result != ChallengeStatus.FAIL) {
            throw new IllegalArgumentException("판정 결과는 SUCCESS/FAIL만 가능: " + result);
        }
        this.status = result;
    }

    /** 포기한 날부터 조회·집계에서 제외하되 목표 종료일은 유지한다. */
    public void giveUp(LocalDate inactiveFrom) {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 포기할 수 있다: " + status);
        }
        if (inactiveFrom == null) {
            throw new IllegalArgumentException("inactiveFrom은 필수입니다.");
        }
        this.status = ChallengeStatus.FAIL;
        this.endReason = EndReason.GIVEN_UP;
        this.inactiveFrom = inactiveFrom;
    }

    /** 미입력 자동 취소로 VOID 처리하고 마지막 미입력일 다음 날부터 날짜 조회 대상에서 제외한다. */
    public void cancelForMissingInput(LocalDate lastMissingDate) {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 자동 취소할 수 있다: " + status);
        }
        if (lastMissingDate == null) {
            throw new IllegalArgumentException("lastMissingDate는 필수입니다.");
        }
        this.status = ChallengeStatus.VOID;
        this.endReason = EndReason.MISSING_DAILY_INPUT;
        this.inactiveFrom = lastMissingDate.plusDays(1);
    }

    public void adjustGoal(int newBudgetTotal, int newDailyLimit) {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 목표를 조정할 수 있다: " + status);
        }
        if (newBudgetTotal < 0) {
            throw new IllegalArgumentException("budgetTotal은 0 이상이어야 합니다: " + newBudgetTotal);
        }
        if (newDailyLimit < 0) {
            throw new IllegalArgumentException("dailyLimit은 0 이상이어야 합니다: " + newDailyLimit);
        }
        this.budgetTotal = newBudgetTotal;
        this.dailyLimit = newDailyLimit;
    }

    /** 기록 기반 결과의 지출 변경을 잠그며 이미 확정된 성패는 바꾸지 않는다. */
    public void lockExpenseChanges(LocalDateTime at) {
        if (isInProgress()) {
            throw new IllegalStateException("결과가 확정된 챌린지만 지출 변경을 잠글 수 있다: " + status);
        }
        if (isExpenseLocked()) {
            throw new IllegalStateException("이미 지출 변경이 잠긴 챌린지다: " + expenseLockedAt);
        }
        this.expenseLockedAt = at;
    }

    public boolean isInProgress() {
        return status == ChallengeStatus.IN_PROGRESS;
    }

    public boolean isExpenseLocked() {
        return expenseLockedAt != null;
    }

    public boolean isFixedDate() {
        return fixedDay != null;
    }

    /** 포기·미입력 취소가 아닌, 지출 기록으로 계산된 결과인지 반환한다. */
    public boolean isResultFromRecords() {
        return endReason == null;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
