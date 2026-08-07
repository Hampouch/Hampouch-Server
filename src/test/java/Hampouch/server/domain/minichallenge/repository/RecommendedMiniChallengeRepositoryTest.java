package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파생 쿼리(기간 필터)를 H2에 실제 적용해 검증.
 * 이 엔티티엔 created_at이 없어(ERD대로) Auditing·Clock 설정 import가 필요 없다.
 */
@DataJpaTest
class RecommendedMiniChallengeRepositoryTest {

    @Autowired
    RecommendedMiniChallengeRepository repository;

    @Test
    @DisplayName("findByDurationDays가 해당 기간의 추천만 찾는다 — 다른 기간 제외, 없는 기간은 빈 결과")
    void findByDurationDays() {
        repository.save(RecommendedMiniChallenge.of("오늘 커피 사먹지 않기", 1));
        repository.save(RecommendedMiniChallenge.of("편의점 디저트 안 먹기", 7));
        repository.save(RecommendedMiniChallenge.of("배달 음식 안 시키기", 7));

        assertThat(repository.findByDurationDays(7))
                .hasSize(2)
                .extracting(RecommendedMiniChallenge::getTitle)
                .containsExactlyInAnyOrder("편의점 디저트 안 먹기", "배달 음식 안 시키기");
        assertThat(repository.findByDurationDays(5)).isEmpty(); // 화이트리스트 밖 = 매칭 없음 → 빈 목록
        assertThat(repository.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("추천 카탈로그(기획이 심는 추천 목록) 행을 저장하면 앱이 아니라 DB가 id를 발급한다")
    // 이 id가 응답의 recommendedId로 매핑되는 것까지는 여기서 확인하지 않는다 —
    // 매핑은 RecommendedMiniChallengeServiceTest, 실제 응답 필드는 통합 테스트가 검증.
    void idGenerated() {
        RecommendedMiniChallenge saved = repository.save(RecommendedMiniChallenge.of("야식 안 먹기", 3));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDurationDays()).isEqualTo(3);
    }
}
