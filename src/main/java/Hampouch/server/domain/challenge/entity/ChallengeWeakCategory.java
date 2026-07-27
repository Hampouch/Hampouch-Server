package Hampouch.server.domain.challenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 온보딩 STEP3에서 선택한 약한 카테고리. 저장 단위 = 챌린지별(결정기록 확정).
 * uq_weak_category = (challenge_id, category) 조합 유니크 — 같은 챌린지에 같은 카테고리 중복 저장을
 * DB가 최종 차단(동시 요청 경쟁까지 방어). 다른 챌린지가 같은 카테고리를 고르는 건 무관.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자(Hibernate가 행→객체 복원·프록시 생성에 사용). protected = 우리 코드의 new 차단, 정식 생성은 Challenge.replaceWeakCategories()
@Entity
@Table(name = "challenge_weak_category", uniqueConstraints =
        @UniqueConstraint(name = "uq_weak_category", columnNames = {"challenge_id", "category"}))
public class ChallengeWeakCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "challenge_id", nullable = false) // 이 테이블에 생기는 FK 컬럼 이름 — challenge.id를 참조(팀 ERD와 이름 계약 고정)
    private Challenge challenge;

    @Column(nullable = false, length = 50)
    private String category;

    ChallengeWeakCategory(Challenge challenge, String category) {
        this.challenge = challenge;
        this.category = category;
    }
}
