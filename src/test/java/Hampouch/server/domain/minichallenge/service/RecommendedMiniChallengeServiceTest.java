package Hampouch.server.domain.minichallenge.service;

import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse;
import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse.Item;
import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 필터·전체·빈 결과·랜덤 노출 순서 검증. 리포지토리는 Mockito 목 — DB 불필요.
 */
@ExtendWith(MockitoExtension.class)
class RecommendedMiniChallengeServiceTest {

    @Mock
    RecommendedMiniChallengeRepository repository;

    /** id는 DB(auto_increment) 발급이라 단위 테스트에선 리플렉션으로 주입해 매핑을 검증한다. */
    private static RecommendedMiniChallenge preset(long id, String title, int durationDays) {
        RecommendedMiniChallenge preset = RecommendedMiniChallenge.of(title, durationDays);
        ReflectionTestUtils.setField(preset, "id", id);
        return preset;
    }

    private RecommendedMiniChallengeService serviceWith(RandomGenerator random) {
        return new RecommendedMiniChallengeService(repository, random);
    }

    @Test
    @DisplayName("durationDays를 주면 그 기간의 추천만 필터되고, 항목이 recommendedId·title·durationDays로 매핑된다")
    void filterByDuration() {
        when(repository.findByDurationDays(7)).thenReturn(List.of(
                preset(6L, "편의점 디저트 안 먹기", 7)));

        RecommendedMiniChallengeListResponse res = serviceWith(new Random(1)).getRecommended(7);

        assertThat(res.items()).hasSize(1);
        Item item = res.items().get(0);
        assertThat(item.recommendedId()).isEqualTo(6L);
        assertThat(item.title()).isEqualTo("편의점 디저트 안 먹기");
        assertThat(item.durationDays()).isEqualTo(7);
        verify(repository, never()).findAll(); // 필터 경로에선 전체 조회가 나가면 안 됨
    }

    @Test
    @DisplayName("durationDays가 없으면 카탈로그 전체가 누락·중복 없이 내려간다")
    void allWhenNoFilter() {
        when(repository.findAll()).thenReturn(List.of(
                preset(1L, "오늘 커피 사먹지 않기", 1),
                preset(2L, "야식 안 먹기", 3),
                preset(3L, "편의점 디저트 안 먹기", 7)));

        RecommendedMiniChallengeListResponse res = serviceWith(new Random(1)).getRecommended(null);

        // 순서는 랜덤이라 묻지 않고(별도 테스트), 구성만 검증
        assertThat(res.items()).extracting(Item::recommendedId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("허용 기간 목록(1·3·7·14·31) 밖 durationDays(예: 5)는 에러가 아니라 빈 items로 내려간다 (명세 §2에 400 없음 — 자체 결정)")
    void emptyWhenOutOfWhitelist() {
        when(repository.findByDurationDays(5)).thenReturn(List.of());

        RecommendedMiniChallengeListResponse res = serviceWith(new Random(1)).getRecommended(5);

        assertThat(res.items()).isEmpty();
    }

    @Test
    @DisplayName("노출 순서는 주입된 난수원이 결정한다 — 같은 시드로 섞은 순서와 정확히 일치 (랜덤 노출 0630 확정)")
    void shuffleUsesInjectedRandom() {
        List<RecommendedMiniChallenge> rows = List.of(
                preset(1L, "가", 7), preset(2L, "나", 7), preset(3L, "다", 7),
                preset(4L, "라", 7), preset(5L, "마", 7), preset(6L, "바", 7));
        when(repository.findByDurationDays(7)).thenReturn(rows);

        RecommendedMiniChallengeListResponse res = serviceWith(new Random(42)).getRecommended(7);

        // 기대 순서 = 같은 시드의 난수원으로 같은 shuffle을 돌린 결과.
        // 변수 타입을 RandomGenerator로 맞춰 서비스와 같은 shuffle(List, RandomGenerator) 오버로드를 태운다.
        List<RecommendedMiniChallenge> expected = new ArrayList<>(rows);
        RandomGenerator sameSeed = new Random(42);
        Collections.shuffle(expected, sameSeed);

        assertThat(res.items()).extracting(Item::recommendedId)
                .containsExactlyElementsOf(expected.stream().map(RecommendedMiniChallenge::getId).toList());
    }
}
