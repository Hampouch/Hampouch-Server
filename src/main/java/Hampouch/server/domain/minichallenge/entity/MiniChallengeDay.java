package Hampouch.server.domain.minichallenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 미니 챌린지 일별 체크 1행 — 행이 존재하면 그날 체크됨, 해제는 행 삭제(ERD MINI_CHALLENGE_DAY).
 * 하루 1행: unique(mini_challenge_id, check_date).
 *
 * ChallengeDay(금액·판정 저장)와 달리 값이 없는 "체크 표시"라 상태 컬럼이 없다 —
 * 행의 존재 자체가 상태이고, 그래서 update 메서드도 없다(체크 재요청은 기존 행 유지, §5).
 */
@Getter
// JPA 필수 빈 생성자 — 스펙이 public 또는 protected를 요구(프록시가 자식 클래스라 private 불가).
// protected 범위 = 같은 패키지 전부 + 다른 패키지의 자식 클래스. 패키지 밖 일반 코드의 new는 막힌다.
// 정식 생성은 of()
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "mini_challenge_day",
        // 두 컬럼의 조합이 유일하다는 뜻이지, 각각이 유일한 게 아니다 — 한 미니가 여러 날짜에 체크되는 것도,
        // 한 날짜가 여러 미니에 체크되는 것도 정상이고 (미니, 날짜) 쌍만 중복될 수 없다. 이게 곧 하루 한 행이다.
        // 적는 이름은 자바 필드명이 아니라 DB 컬럼명 — mini_challenge_id는 아래 @JoinColumn, check_date는 @Column에서 온 이름이라
        // 그쪽 이름을 바꾸면 여기도 같이 바꿔야 한다.
        // 자바가 아니라 DB가 막게 한 이유: 서비스가 체크 여부를 조회한 뒤 저장하는 사이에 같은 요청이 또 들어오면
        // 둘 다 없음을 보고 둘 다 저장할 수 있다. 그 틈은 코드로 못 막아서 DB 제약이 최후 방어선이다.
        // 제약 이름을 직접 준 건 생략하면 Hibernate가 임의 이름을 붙여 ERD·팀 문서와 어긋나기 때문.
        // 걸리는 경로: 이 선언은 설계도일 뿐 → 기동 시 Hibernate 스키마 도구(ddl-auto=update)가 DDL로 만들어 DB에 걸고
        // → 그 뒤 집행은 DB 엔진 몫(MySQL은 유니크 인덱스로 구현, JPA 안 거친 INSERT도 막힘)
        // → 위반 시 JDBC 예외를 스프링이 DataIntegrityViolationException으로 번역해 자바로 돌아온다.
        uniqueConstraints = @UniqueConstraint(name = "uq_mini_challenge_day", columnNames = {"mini_challenge_id", "check_date"})
)
// checkedAt(@CreatedDate)을 저장 직전에 공용 Clock 기준으로 자동 기입. 애너테이션 자체 설명은 MiniChallenge 참고
@EntityListeners(AuditingEntityListener.class)
public class MiniChallengeDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // optional은 기본값이 true(없어도 됨)라 꺼준 것 — 주인 없는 체크 행은 "무엇을 체크했는지 모르는 체크"라 모순이다.
    // 아래 nullable = false와 중복인데 의도된 중복이다. Hibernate가 부팅 때 둘을 AND로 합쳐서 하나만 있어도 결과가 같고,
    // 모순되게 줘도 "안 된다"는 쪽이 이긴다. 표준 스펙의 공식 예제도 둘을 같이 쓰므로 그대로 둔다.
    // 위반은 DB가 잡는다(제약 위반). INSERT 전에 Hibernate가 먼저 잡아주는 검사는 이 프로젝트에선 꺼져 있다 —
    // Bean Validation이 클래스패스에 있으면 Hibernate가 check_nullability를 자동으로 끄기 때문(아무도 설정한 적 없는 기본 동작).
    // optional = false는 LAZY와 무관하다. 그건 @OneToOne 얘기고 @ManyToOne은 fetch = LAZY 혼자로 충분하다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mini_challenge_id", nullable = false) // 이 테이블에 생기는 FK 컬럼 이름 — mini_challenge.id 참조(팀 ERD와 이름 계약 고정)
    private MiniChallenge miniChallenge;

    /** 어느 날의 체크인지(체크 대상 날짜) — 체크한 시각(checkedAt)과 다름, 과거 날짜 늦은 체크 가능(0707 확정). */
    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    /**
     * 체크한 시각. 행 생성 = 체크이므로 @CreatedDate로 자동 기입되고,
     * 같은 날 재체크는 기존 행을 그대로 둬서 최초 체크 시각이 보존된다(명세 §5: 재요청 시 checked_at 보존).
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedAt;

    private MiniChallengeDay(MiniChallenge miniChallenge, LocalDate checkDate) {
        this.miniChallenge = miniChallenge;
        this.checkDate = checkDate;
    }

    public static MiniChallengeDay of(MiniChallenge miniChallenge, LocalDate checkDate) {
        return new MiniChallengeDay(miniChallenge, checkDate);
    }
}
