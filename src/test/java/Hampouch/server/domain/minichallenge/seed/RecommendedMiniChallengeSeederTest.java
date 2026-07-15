package Hampouch.server.domain.minichallenge.seed;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시더의 "비어 있을 때만 insert" 규칙을 H2에 실제 적용해 검증.
 *
 * 시더를 @Import로 빈 등록하지 않고 직접 new 하는 이유: 빈으로 두면 ApplicationRunner라
 * 테스트 컨텍스트 기동 시점에 스프링이 run()을 먼저 호출해 버린다 — 그 시딩은 테스트
 * 트랜잭션(롤백) 밖에서 커밋돼 "빈 카탈로그에서 시작"하는 시나리오를 만들 수 없다.
 * 직접 만들면 시딩이 각 테스트의 롤백 트랜잭션 안에서만 일어난다.
 * "기동 시 러너가 실제로 실행되는지"는 통합 테스트(RecommendedMiniChallengeFlowIntegrationTest)가 검증.
 */
@DataJpaTest
class RecommendedMiniChallengeSeederTest {

    @Autowired
    RecommendedMiniChallengeRepository repository;

    RecommendedMiniChallengeSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new RecommendedMiniChallengeSeeder(repository);
    }

    @Test
    @DisplayName("빈 카탈로그에 시드가 들어가고, 기간 화이트리스트(1·3·7·14·31일) 전부에 추천이 존재한다")
    void seedsWhenEmpty() throws Exception {
        seeder.run(new DefaultApplicationArguments());

        List<RecommendedMiniChallenge> all = repository.findAll();
        assertThat(all).isNotEmpty();
        assertThat(all).extracting(RecommendedMiniChallenge::getDurationDays)
                .containsAll(List.of(1, 3, 7, 14, 31)); // 어느 기간 탭을 눌러도 빈 화면이 아니게
        assertThat(all).allSatisfy(preset -> {
            assertThat(preset.getDurationDays()).isIn(1, 3, 7, 14, 31); // 화이트리스트 밖 기간이 시드에 없음
            assertThat(preset.getTitle()).isNotBlank();
        });
    }

    @Test
    @DisplayName("두 번 실행해도 시드가 중복으로 쌓이지 않는다 — 재기동 대비 멱등")
    void idempotentOnRerun() throws Exception {
        seeder.run(new DefaultApplicationArguments());
        long afterFirst = repository.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(repository.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("카탈로그에 이미 데이터가 있으면 시더는 아무것도 넣지 않는다 — 기획·운영이 넣어 둔 실데이터를 덮지 않고 보존한다")
    void skipsWhenNotEmpty() throws Exception {
        repository.save(RecommendedMiniChallenge.of("기존 추천", 3));

        seeder.run(new DefaultApplicationArguments());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().get(0).getTitle()).isEqualTo("기존 추천");
    }
}
