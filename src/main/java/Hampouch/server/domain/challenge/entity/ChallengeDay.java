package Hampouch.server.domain.challenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 일별 판정 1행 (캘린더·결과 집계용). 하루 1행: unique(challenge_id, day_date).
 *
 * spentAmount는 그날 EXPENSE(외부) 합계지만, Phase 1에서는 POST /days로 직접 받는다.
 * TODO(령준 지출 연동): spentAmount 출처를 EXPENSE 합계 조회로 교체.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자(Hibernate가 행→객체 복원·프록시 생성에 사용). protected = 우리 코드의 new 차단, 정식 생성은 of()
@Entity
@Table(
        name = "challenge_day",
        uniqueConstraints = @UniqueConstraint(name = "uq_challenge_day", columnNames = {"challenge_id", "day_date"})
)
public class ChallengeDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "challenge_id", nullable = false) // 이 테이블에 생기는 FK 컬럼 이름 — challenge.id를 참조(팀 ERD와 이름 계약 고정)
    private Challenge challenge;

    /** 이 행이 "어느 날"의 기록인지(판정 대상 날짜) — 입력 시각(createdAt 개념)이 아님, 과거 날짜 늦은 입력 가능. 컬럼명 day_date는 date가 예약어라 회피. */
    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(nullable = false)
    private int spentAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DayStatus status;

    /**
     * 이 행을 판정할 때 쓴 하루 한도 스냅샷. 목표 조정(#7)이 지난 날의 판정·절약액을 소급해 바꾸지 않도록 행에 새긴다.
     * 미기록일은 행이 없으므로 조정 이력에서 그날 한도를 복원한다.
     */
    @Column(nullable = false)
    private int dailyLimit;

    // 파라미터가 5개지만 빌더를 두지 않은 건, 인접한 같은 타입이 없어(Challenge·LocalDate·int·DayStatus·int)
    // 인자 순서가 밀리면 컴파일러가 전부 잡아내기 때문이다 — Challenge가 빌더를 쓰는 이유였던 위험이 여기엔 없다.
    private ChallengeDay(Challenge challenge, LocalDate dayDate, int spentAmount, DayStatus status, int dailyLimit) {
        this.challenge = challenge;
        this.dayDate = dayDate;
        this.spentAmount = spentAmount;
        this.status = status;
        this.dailyLimit = dailyLimit;
    }

    /** 판정(status)은 호출부에서 ChallengeCalculator.judge로 계산해 넘긴다(규칙 단일 출처). dailyLimit은 그 판정에 쓴 한도. */
    public static ChallengeDay of(Challenge challenge, LocalDate dayDate, int spentAmount, DayStatus status,
                                  int dailyLimit) {
        return new ChallengeDay(challenge, dayDate, spentAmount, status, dailyLimit);
    }

    /** 같은 날 지출 재입력(upsert) — 한도는 그대로 두고 금액과 그에 따른 판정만 덮어쓴다. */
    public void update(int spentAmount, DayStatus status) {
        this.spentAmount = spentAmount;
        this.status = status;
    }

    /** 한도가 바뀌어(#7 조정) 다시 채점된 경우 — 지출은 그대로고 판정 기준과 결과가 함께 바뀐다. */
    public void rejudge(DayStatus status, int dailyLimit) {
        this.status = status;
        this.dailyLimit = dailyLimit;
    }
}
