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
 * 미니 챌린지 1건 — 유저 소유, 본 챌린지와 독립(challenge_id 없음).
 * 추천에서 왔든 커스텀이든 같은 행(origin 구분 컬럼 없음).
 * 내용 수정 불가(확정 사양: 삭제 후 새로 생성) — 그래서 수정 메서드가 없다.
 * endDate는 startDate + durationDays - 1 스냅샷 — 규칙이 바뀌어도 기존 행의 기간이 흔들리지 않게.
 */
@Getter
// JPA 스펙상 기본 생성자는 protected 이상 — 정식 생성 통로는 create()
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mini_challenge")
// 이 줄이 빠지면 @CreatedDate가 안 채워져 createdAt의 nullable=false 위반으로 저장이 터진다
@EntityListeners(AuditingEntityListener.class)
public class MiniChallenge {

    /**
     * 제목 길이 상한 — 명세·ERD에 없어 서버가 정한 저장 방어값이다(화면 입력 제한은 안드 몫).
     * 커스텀 생성 검증(MiniChallengeService)과 추천 카탈로그 컬럼이 이 상수를 같이 쓴다 — 값을 바꾸면 셋이 함께 움직인다.
     */
    public static final int TITLE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT principal에서 오는 유저 id. TODO: @ManyToOne User 연관 전환은 별도 결정 대기 — Challenge와 동일 패턴. */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 기간(일) — 화이트리스트 검증의 단일 출처는 MiniChallengeService.ALLOWED_DURATIONS. */
    @Column(nullable = false)
    private int durationDays;

    /** 시작일 = 추가한 날(오늘). */
    @Column(nullable = false)
    private LocalDate startDate;

    /** startDate + durationDays - 1 스냅샷. */
    @Column(nullable = false)
    private LocalDate endDate;

    /** updatable = false — UPDATE 문에서 아예 빼 실수로도 안 덮이게. 수정 불가 엔티티라 @LastModifiedDate는 없다. */
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

    /** 유일한 생성 통로 — endDate 스냅샷 계산을 우회한 객체가 못 생기게 생성자는 private. */
    public static MiniChallenge create(Long userId, String title, int durationDays, LocalDate startDate) {
        return new MiniChallenge(userId, title, durationDays, startDate);
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isActiveOn(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
