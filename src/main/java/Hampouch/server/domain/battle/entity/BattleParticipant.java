package Hampouch.server.domain.battle.entity;

import Hampouch.server.domain.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 배틀 참가자 — 배틀 생성 시 생성자도 이 테이블에 함께 등록
 * rank/totalAmount는 TERMINATED 스냅샷 전엔 항상 null.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "battle_participant",
        uniqueConstraints = @UniqueConstraint(name = BattleParticipant.PARTICIPANT_UNIQUE, columnNames = {"battle_id", "user_id"}))
@EntityListeners(AuditingEntityListener.class)
public class BattleParticipant {

    public static final String PARTICIPANT_UNIQUE = "uq_battle_participant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long id;

    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "is_valid", nullable = false)
    private boolean isValid;

    /** 배틀 종료 시점 순위 — TERMINATED 스냅샷 전엔 null*/
    @Column(name = "final_rank")
    @Max(10)
    private Integer rank;

    /** 배틀 기간 동안 사용한 금액 */
    @Column(name = "total_amount")
    private Integer totalAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "battle_id", nullable = false)
    private Battle battle;

    private BattleParticipant(User user, Battle battle) {
        this.user = user;
        this.battle = battle;
        this.isValid = true; // ERD 기본값 true — 무효화는 배치가 3일 연속 미기록 확인 후 invalidate()로만
    }

    public static BattleParticipant of(User user, Battle battle) {
        return new BattleParticipant(user, battle);
    }

    // 3일 연속 미기록 시 무효화
    public void invalidate() {
        this.isValid = false;
    }

    /**
     * 종료일 배치 전용 스냅샷 — Battle.terminate()와 같은 트랜잭션에서 참가자별로 호출.
     * rank는 무효화(isValid=false)된 참가자의 경우 랭킹 경쟁에서 제외한다는 의미로 null을 허용
     */
    public void finalizeResult(Integer rank, int totalAmount) {
        this.rank = rank;
        this.totalAmount = totalAmount;
    }

    public boolean isOwnedBy(Long userId) {
        return this.user.getId().equals(userId);
    }
}
