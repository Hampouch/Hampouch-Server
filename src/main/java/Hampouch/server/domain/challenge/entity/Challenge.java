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
import java.util.ArrayList;
import java.util.List;

/** dailyLimit은 현재 한도이며, 지난 날짜의 한도는 ChallengeDay에 스냅샷으로 보존한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "challenge",
        uniqueConstraints = @UniqueConstraint(name = "uq_challenge_active_user", columnNames = "active_user_id"))
@EntityListeners(AuditingEntityListener.class)
public class Challenge {

    /**
     * 목표 금액의 요청 상한(원). 0727 자체 결정이 금액 필드(목표·일별 지출)에 공통으로 준 값이고, 유도는 일별 지출 쪽이다 —
     * 100일치를 int로 누산하면 21.4억을 넘겨서 안전 경계가 약 2,147만이고 그 절반을 잡았다. 목표 금액만 놓고 보면 훨씬 헐거운
     * 값이라 여기서 오버플로를 읽어내지 말 것(조정 배율이 int를 넘기려면 약 17.9억이 필요하다).
     * 엔티티 불변식도 아니다 — 프리셋 조정이 배율을 곱하므로 저장된 budgetTotal은 이 값을 넘을 수 있다(상한 × 1.2² = 14,400,000).
     */
    public static final int BUDGET_TOTAL_MAX = 10_000_000;

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

    /** 기간 내 식비 목표(원). */
    @Column(nullable = false)
    private int budgetTotal;

    /** 현재 하루 한도(원). */
    @Column(nullable = false)
    private int dailyLimit;

    @Column(nullable = false)
    private boolean resetByPayday;

    private Integer paydayDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeStatus status;

    /**
     * MySQL 조건부 유니크 우회용 생성 컬럼. 진행 중일 때만 userId를 노출해 유저당 진행 중 챌린지 1개를 강제한다.
     * STORED/VIRTUAL은 H2 호환을 위해 생략하며 MySQL에서는 VIRTUAL로 생성된다.
     */
    @Column(name = "active_user_id", insertable = false, updatable = false,
            columnDefinition = "bigint generated always as (case when status = 'IN_PROGRESS' then user_id end)")
    private Long activeUserId;

    /** null이면 기록 기반 판정이라 재계산할 수 있고, 값이 있으면 선언 또는 자동 취소라 재계산하지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EndReason endReason;

    /**
     * 유저가 결과 팝업에서 [챌린지 종료]를 누른 시각 — null이면 아직 안 눌렀다는 뜻이다.
     * status와 다른 축이다: status는 성패(기간이 끝나면 조회가 확정)이고 이 값은 잠금이라,
     * 기간이 끝나 SUCCESS로 확정된 뒤에도 유저가 누르기 전까지는 지출을 마저 고칠 수 있다.
     */
    private LocalDateTime closedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "challenge", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChallengeWeakCategory> weakCategories = new ArrayList<>();

    /** 필수값 검증과 endDate·status 초기화를 우회하지 못하도록 생성자에만 빌더를 노출한다. */
    @Builder
    private Challenge(Long userId, int durationDays, LocalDate startDate, int budgetTotal,
                      int dailyLimit, boolean resetByPayday, Integer paydayDay) {
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
        this.userId = userId;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = startDate.plusDays(durationDays - 1L);
        this.budgetTotal = budgetTotal;
        this.dailyLimit = dailyLimit;
        this.resetByPayday = resetByPayday;
        this.paydayDay = paydayDay;
        this.status = ChallengeStatus.IN_PROGRESS;
    }

    /** 기존 행을 재사용해 Hibernate의 INSERT-before-orphan-DELETE 순서에서 유니크 충돌을 피한다. */
    public void replaceWeakCategories(List<String> categories) {
        List<ChallengeWeakCategory> next = categories.stream()
                .distinct()
                .map(this::reuseOrCreateWeakCategory)
                .toList();
        this.weakCategories.clear();
        this.weakCategories.addAll(next);
    }

    private ChallengeWeakCategory reuseOrCreateWeakCategory(String category) {
        return this.weakCategories.stream()
                .filter(w -> category.equals(w.getCategory()))
                .findFirst()
                .orElseGet(() -> new ChallengeWeakCategory(this, category));
    }

    /** 기록 기반 SUCCESS/FAIL 판정만 반영하며 endReason은 설정하지 않는다. */
    public void applyResult(ChallengeStatus result) {
        if (result != ChallengeStatus.SUCCESS && result != ChallengeStatus.FAIL) {
            throw new IllegalArgumentException("판정 결과는 SUCCESS/FAIL만 가능: " + result);
        }
        this.status = result;
    }

    /** 포기 표식을 남겨 이후 지출 수정 재계산이 결과를 바꾸지 못하게 하며, 목표 종료일은 보존한다. */
    public void giveUp() {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 포기할 수 있다: " + status);
        }
        this.status = ChallengeStatus.FAIL;
        this.endReason = EndReason.GIVEN_UP;
    }

    /** 미입력 자동 취소로 표시해 히스토리와 재계산 대상에서 제외한다. */
    public void cancelForMissingInput() {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 자동 취소할 수 있다: " + status);
        }
        this.status = ChallengeStatus.VOID;
        this.endReason = EndReason.MISSING_DAILY_INPUT;
    }

    /** 현재 목표와 한도만 바꾸며 지난 날짜의 한도는 ChallengeDay 스냅샷을 유지한다. */
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

    /**
     * 최종 종료 — 유저 선언으로 기록을 잠근다. 성패는 이미 정해져 있으므로 status는 건드리지 않는다.
     * 클라 요청 오류(409)는 서비스가 먼저 거르므로 여기 검사에 걸리면 서버 코드 실수다(giveUp과 같은 원칙).
     */
    public void close(LocalDateTime at) {
        if (isInProgress()) {
            throw new IllegalStateException("결과가 확정된 챌린지만 최종 종료할 수 있다: " + status);
        }
        if (isClosed()) {
            throw new IllegalStateException("이미 최종 종료된 챌린지다: " + closedAt);
        }
        this.closedAt = at;
    }

    public boolean isInProgress() {
        return status == ChallengeStatus.IN_PROGRESS;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /**
     * 결과가 일별 기록에서 계산된 것인가 — 그래서 지출을 고치면 재계산되고, 최종 종료로 얼릴 대상이 된다.
     * 포기(GIVEN_UP)·자동 취소(MISSING_DAILY_INPUT)는 선언·제재라 endReason이 채워지고 재계산에서 빠진다.
     * "endReason == null" 을 호출부마다 직접 쓰지 않고 이 이름으로 물어 의도를 드러낸다(재계산 가드·최종 종료 가드 공용).
     */
    public boolean isResultFromRecords() {
        return endReason == null;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
