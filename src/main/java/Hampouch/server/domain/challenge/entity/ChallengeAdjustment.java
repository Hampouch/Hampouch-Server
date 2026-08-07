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

/**
 * 목표 금액 조정 이력 1건.
 *
 * 이력을 남기는 이유가 두 가지다 — 조정 가능 횟수(기간별 1~2회)를 세는 근거이고,
 * 기록이 없는 날(미입력일)의 그날 한도를 되살리는 근거다. 기록이 있는 날은 ChallengeDay 행이
 * 자기 한도를 스냅샷으로 갖지만, 행이 없는 날은 이 이력에서 역산하는 수밖에 없다.
 *
 * 하루 한도는 목표 금액에서 파생되지만 계산 결과도 함께 남긴다 — 파생 공식이 나중에 바뀌어도
 * 지난 조정이 그때 실제로 적용한 한도가 무엇이었는지는 흔들리면 안 되기 때문이다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자. protected = 우리 코드의 new 차단, 정식 생성은 빌더
@Entity
@Table(name = "challenge_adjustment",
        uniqueConstraints = @UniqueConstraint(name = "uq_challenge_adjustment_seq",
                columnNames = {"challenge_id", "sequence_number"}))
@EntityListeners(AuditingEntityListener.class)
public class ChallengeAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;

    /**
     * 이 챌린지의 몇 번째 조정인가(1부터). 순서를 보려고 두는 게 아니라 위 유니크 제약과 한 쌍으로
     * 동시 요청을 막으려고 둔다 — 횟수 검사와 저장이 갈라져 있어 두 요청이 같은 횟수를 읽고 함께 통과할 수 있는데,
     * 그러면 둘 다 같은 번호를 쓰게 되어 한쪽이 제약에 걸린다. 서비스가 그 위반을 409로 바꾼다.
     */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    /** 새 목표가 적용되기 시작하는 날 = 조정한 날. 이 날부터가 새 한도이고 그 앞은 조정 전 한도다(과거 소급 없음). */
    @Column(nullable = false)
    private LocalDate effectiveDate;

    /** 고른 프리셋. 직접 입력으로 조정하면 배율이 없어 null이다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AdjustOption option;

    /** 조정 직전 목표. 첫 조정 행의 이전 값들이 "기간 시작 시점"이라 타임라인 복원의 기준점이 된다. */
    @Column(nullable = false)
    private int previousBudgetTotal;

    @Column(nullable = false)
    private int newBudgetTotal;

    @Column(nullable = false)
    private int previousDailyLimit;

    @Column(nullable = false)
    private int newDailyLimit;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 같은 int가 넷 연달아 있어(이전/이후 목표·이전/이후 한도) 인자 순서가 밀려도 컴파일러가 못 잡는다 — Challenge와 같은 이유로 빌더를 쓴다
    @Builder
    private ChallengeAdjustment(Challenge challenge, int sequenceNumber, LocalDate effectiveDate, AdjustOption option,
                                int previousBudgetTotal, int newBudgetTotal,
                                int previousDailyLimit, int newDailyLimit) {
        this.challenge = challenge;
        this.sequenceNumber = sequenceNumber;
        this.effectiveDate = effectiveDate;
        this.option = option;
        this.previousBudgetTotal = previousBudgetTotal;
        this.newBudgetTotal = newBudgetTotal;
        this.previousDailyLimit = previousDailyLimit;
        this.newDailyLimit = newDailyLimit;
    }
}
