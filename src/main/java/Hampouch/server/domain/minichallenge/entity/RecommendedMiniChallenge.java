package Hampouch.server.domain.minichallenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 미니 챌린지 프리셋 카탈로그 1건 (ERD RECOMMENDED_MINI_CHALLENGE).
 *
 * 기획이 심어 두는 고정 목록(읽기 전용) — 유저가 "추가하기" 하면 이 행의 값(title·durationDays)만
 * 유저의 mini_challenge 행으로 복사되고(API명세_미니챌린지.md §3), 이 테이블과의 FK 관계는 두지 않는다(0707 본챌 독립화).
 * created_at 없음 — ERD대로. 운영 중 유저가 만드는 데이터가 아니라 생성 시각 추적이 불필요해
 * JPA Auditing도 안 쓴다.
 */
@Getter // 필드별 getter 자동 생성(팀 스타일)
// JPA 필수 빈 생성자 — 스펙(Jakarta Persistence)이 public 또는 protected를 요구한다.
// Hibernate가 지연 로딩 프록시를 "이 엔티티를 상속한 자식 클래스"로 만들기 때문에 private는 안 된다.
// protected의 호출 가능 범위 = 같은 패키지 전부 + 다른 패키지의 자식 클래스(생성자는 super() 경유만).
// 즉 서비스 등 패키지 밖 일반 코드의 반쪽짜리 new는 막힌다. 정식 생성은 of()
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recommended_mini_challenge")
public class RecommendedMiniChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 번호 발급은 DB(auto_increment) 몫
    private Long id;

    /** 추천 문구. 예: 편의점 디저트 안 먹기 */
    @Column(nullable = false)
    private String title;

    /** 기간(일). 기간 탭 화이트리스트 = 오늘만 1 / 3 / 7 / 14 / 31 (명세 §2 확정) */
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
