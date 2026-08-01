package Hampouch.server.domain.minichallenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 미니 챌린지 프리셋 카탈로그 1건 — 기획이 심어 두는 고정 목록(읽기 전용).
 * 유저가 추가하면 이 행의 title·durationDays 값만 유저의 mini_challenge 행으로 복사되고 FK 관계는 두지 않는다.
 * 유저가 만드는 데이터가 아니라 created_at·Auditing이 없다.
 */
@Getter
// JPA 스펙상 기본 생성자는 protected 이상 — 정식 생성 통로는 of()
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recommended_mini_challenge")
public class RecommendedMiniChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 추천 문구. 예: 편의점 디저트 안 먹기
     * 길이 상한을 mini_challenge.title과 같은 값으로 묶는다 — 추가할 때 이 값이 그대로 복사되므로,
     * 여기가 더 길면 복사되는 쪽 컬럼에서 저장이 터진다.
     */
    @Column(nullable = false, length = MiniChallenge.TITLE_MAX_LENGTH)
    private String title;

    /** 기간(일). */
    @Column(nullable = false)
    private int durationDays;

    private RecommendedMiniChallenge(String title, int durationDays) {
        this.title = title;
        this.durationDays = durationDays;
    }

    /** 카탈로그 행 생성 — 시더와 테스트에서만 쓴다(API로는 읽기 전용 조회만 제공). */
    public static RecommendedMiniChallenge of(String title, int durationDays) {
        return new RecommendedMiniChallenge(title, durationDays);
    }
}
