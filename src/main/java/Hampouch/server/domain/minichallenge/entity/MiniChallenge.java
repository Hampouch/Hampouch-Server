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
 * 미니 챌린지 1건 — 유저 소유, 본 챌린지와 독립(0707 서버회의: 정의+참여 합침, challenge_id 없음).
 *
 * 추천에서 왔든 커스텀이든 같은 행(origin 컬럼 없음 — 추천/커스텀 통일, 0707).
 * 내용 수정 불가(0630 확정: 삭제 후 새로 생성) — 그래서 수정 메서드가 없다.
 * endDate는 startDate + durationDays - 1 스냅샷 — 나중에 규칙이 바뀌어도 기존 행의 기간이 흔들리지 않게.
 */
@Getter // 필드별 getter 자동 생성(나연 common과 동일한 팀 스타일)
// JPA 필수 빈 생성자(Hibernate가 행→객체 복원·프록시 생성에 사용) — 스펙이 public 또는 protected를
// 요구한다. 지연 로딩 프록시가 이 엔티티를 상속한 자식 클래스라서 private면 안 된다.
// protected의 호출 가능 범위 = 같은 패키지 전부 + 다른 패키지의 자식 클래스(생성자는 super() 경유만).
// 즉 서비스 등 패키지 밖 일반 코드의 반쪽짜리 new는 막힌다. 정식 생성은 create()
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mini_challenge")
// @EntityListeners: 엔티티 생명주기 이벤트(저장 직전 등)의 콜백을 엔티티 밖 클래스에 위임하는 JPA 표준.
// AuditingEntityListener는 스프링이 제공하는 그 콜백 구현체로, 저장 직전에 끼어들어 @CreatedDate(createdAt)를 채운다.
// 셋이 한 세트다 — 기능 켜기는 JpaAuditingConfig(@EnableJpaAuditing), 이 줄은 이 엔티티에 리스너 붙이기,
// @CreatedDate는 채울 필드 지목. 이 줄이 빠지면 아무도 채우지 않아 createdAt이 null → nullable=false 위반.
// 채우는 시각은 공용 Clock(Asia/Seoul) — 엔티티가 직접 now()를 부르지 않게 해서 테스트에서 시각을 고정할 수 있다.
@EntityListeners(AuditingEntityListener.class)
public class MiniChallenge {

    /**
     * 제목 길이 상한 — 명세·ERD에 없어 서버가 정한 저장 방어값이다(화면 입력 제한은 안드 몫).
     * 커스텀 생성 검증(MiniChallengeService)과 추천 카탈로그 컬럼이 이 상수를 같이 쓴다 — 값을 바꾸면 셋이 함께 움직인다.
     */
    public static final int TITLE_MAX_LENGTH = 255;

    @Id
    // @GeneratedValue는 "이 값은 내가 안 넣을 테니 알아서 채워라"는 위임일 뿐, 누가 채우는지는 strategy가 정한다.
    // UUID를 골랐으면 자바가 만들지만 IDENTITY는 DB의 auto_increment 몫 — 그래서 create()로 갓 만든 객체의 id는
    // null이고, save()가 INSERT를 날려 DB가 매긴 번호를 돌려받아야 채워진다. MySQL엔 시퀀스가 없어 사실상 이게 정답
    // (AUTO였다면 채번 테이블로 떨어져 더 무겁다). 대신 롤백에 소모된 번호는 재사용되지 않아 중간에 구멍이 뚫리니,
    // 순서나 개수를 세는 용도로 쓰면 안 되고 고유 식별자로만 쓴다.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부(로그인=나연)에서 오는 유저 식별. TODO(로그인 연동): @ManyToOne User 로 교체 — Challenge와 동일 스텁 패턴. */
    @Column(nullable = false)
    private Long userId;

    /** 제목 — 추천에서 복사됐든 커스텀이든 동일(ERD MINI_CHALLENGE.title). */
    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 기간(일). 화이트리스트 {1, 3, 7, 14, 31} 검증은 서비스가 담당(규칙 단일 출처는 MiniChallengeService.ALLOWED_DURATIONS). */
    @Column(nullable = false)
    private int durationDays;

    /** 시작일 = 추가한 날(명세 §3 자체 결정: start_date = 오늘). */
    @Column(nullable = false)
    private LocalDate startDate;

    /** startDate + durationDays - 1 스냅샷. */
    @Column(nullable = false)
    private LocalDate endDate;

    /**
     * 생성 시각. @CreatedDate는 JPA 표준이 아니라 스프링 것(org.springframework.data.annotation)이라
     * Spring Data를 쓰면 JPA 밖에서도 통한다. 값을 채우는 게 아니라 "이 필드가 생성 시각"이라고 표시만 하고,
     * 실제로 채우는 건 클래스에 붙인 AuditingEntityListener다.
     * 최초 INSERT 때 한 번만 채워지므로 updatable = false로 UPDATE 문에서 아예 빼 실수로도 덮이지 않게 한다.
     * 형제인 @LastModifiedDate를 안 쓰는 건 미니 챌린지가 수정 불가(0630 확정, 삭제 후 재생성)라 마지막 수정 시각이
     * 있을 수 없어서다.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private MiniChallenge(Long userId, String title, int durationDays, LocalDate startDate) {
        this.userId = userId;
        this.title = title;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = startDate.plusDays(durationDays - 1L);
    }

    /**
     * 미니 챌린지 생성 — Challenge.create와 같은 관례로, 생성자를 private으로 잠그고
     * 이름 있는 정적 팩토리를 유일한 생성 통로로 둔다(endDate 스냅샷 계산을 우회한 객체가 못 생기게).
     */
    public static MiniChallenge create(Long userId, String title, int durationDays, LocalDate startDate) {
        return new MiniChallenge(userId, title, durationDays, startDate);
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 그 날짜에 활성(기간에 걸쳐 있는)인지 — start ≤ date ≤ end. */
    public boolean isActiveOn(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
