package Hampouch.server.domain.battle.entity;

import Hampouch.server.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 햄배틀 entity. capacity 미달 자동취소·시작 전환·종료 스냅샷은 전부 배치가
 * 명시적으로 호출하는 상태 전이 메서드(start/cancel/terminate)를 통해서만 일어난다
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "battle",
        uniqueConstraints = @UniqueConstraint(name = "uq_battle_code", columnNames = "battle_code"))
@EntityListeners(AuditingEntityListener.class)
public class Battle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "battle_id")
    private Long id;

    @Column(name = "battle_code", nullable = false, length = 30)
    private String battleCode; // 재발급 없는 영구 코드

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "party_capacity", nullable = false)
    private int capacity;

    @Column(name = "duration", nullable = false)
    private int durationDays;

    @Column(nullable = false)
    private LocalDate startDate;

    /** startDate + durationDays - 1, 생성 시점 스냅샷 */
    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 100)
    private String penalty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BattleStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 생성자 — API 응답엔 노출 안 하지만(화면에 불필요) ERD상 NOT NULL 컬럼이라 엔티티엔 유지. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** 꼴찌(벌칙 대상) — TERMINATED 전엔 항상 null, terminate()가 유일한 세팅 통로.
     * ONGOING의 경우 해당 column이 아닌 Query문을 통해 penaltyUser 도출
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "penalty_user_id")
    private User penaltyUser;

    private Battle(String battleCode, String title, int capacity, int durationDays,
                    LocalDate startDate, String penalty, User creator) {
        this.battleCode = battleCode;
        this.title = title;
        this.capacity = capacity;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = startDate.plusDays(durationDays - 1L);
        this.penalty = penalty;
        this.creator = creator;
        this.status = BattleStatus.READY;
    }

    public static Battle of(String battleCode, String title, int capacity, int durationDays,
                             LocalDate startDate, String penalty, User creator) {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate는 필수입니다.");
        }
        return new Battle(battleCode, title, capacity, durationDays, startDate, penalty, creator);
    }

    /** 시작일 배치 전용 — 정원 충족 확인 후 호출. READY가 아니면 배치 로직 버그라 즉시 터뜨림. */
    public void start() {
        if (status != BattleStatus.READY) {
            throw new IllegalStateException("READY 상태만 시작할 수 있습니다: " + status);
        }
        this.status = BattleStatus.ONGOING;
    }

    /** 시작일 배치 전용 — 정원 미달 확인 후 호출. 수동 취소는 없음 */
    public void cancel() {
        if (status != BattleStatus.READY) {
            throw new IllegalStateException("READY 상태만 취소할 수 있습니다: " + status);
        }
        this.status = BattleStatus.CANCELLED;
    }

    /**
     * 종료일 배치 전용. penaltyUser는 여기서만 세팅되는 확정 스냅샷
     */
    public void terminate(User penaltyUser) {
        if (status != BattleStatus.ONGOING) {
            throw new IllegalStateException("ONGOING 상태만 종료할 수 있습니다: " + status);
        }
        this.status = BattleStatus.TERMINATED;
        this.penaltyUser = penaltyUser;
    }

    public boolean isOwnedBy(Long userId) {
        return this.creator.getId().equals(userId);
    }
}
