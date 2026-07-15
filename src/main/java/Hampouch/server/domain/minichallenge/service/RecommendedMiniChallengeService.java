package Hampouch.server.domain.minichallenge.service;

import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse;
import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

@Service
@RequiredArgsConstructor // final 필드들을 받는 생성자 자동 생성 — 스프링이 그 생성자로 의존성 주입(팀 스타일)
// 클래스의 모든 public 메서드를 트랜잭션 경계로 감싼다 — 진입 시 시작, 정상 리턴이면 커밋,
// 런타임 예외면 롤백(스프링이 프록시로 메서드 앞뒤를 가로채서 처리).
// readOnly = true는 "이 트랜잭션엔 쓰기가 없다"는 선언 — 실수로 쓰면 막아 주는 안전장치이자,
// Hibernate가 변경 감지용 스냅샷·flush를 생략해 조회가 가벼워지는 성능 힌트.
// 카탈로그는 읽기 전용이라 이 서비스엔 쓰기 메서드가 없다(시드는 Seeder 몫).
@Transactional(readOnly = true)
public class RecommendedMiniChallengeService {

    private final RecommendedMiniChallengeRepository repository;

    // 무작위 노출(0630 확정)의 난수원. 서비스가 직접 new Random() 하지 않고 빈으로 주입받는 이유는
    // 본챌 #1의 Clock과 동일 — 테스트에서 시드 고정 Random으로 갈아끼워 섞인 순서를 재현·검증하기 위함.
    // 빈 등록은 RandomGeneratorConfig 참조.
    private final RandomGenerator random;

    /**
     * 추천 목록 조회(API명세_미니챌린지.md §2). durationDays가 없으면(널) 카탈로그 전체, 있으면 그 기간만.
     *
     * 화이트리스트(1·3·7·14·31) 밖 durationDays는 에러가 아니라 빈 items(200)로 응답 — 자체 결정.
     * 근거: 명세 §2가 에러로 401만 정의하고 400을 정의하지 않았고, 이 파라미터는 기간 탭 UI에서
     * 오는 값이라 밖의 값 자체가 비정상 호출이며, 그 경우에도 "그 기간의 추천이 없다"는 빈 목록이
     * 의미상 정확한 답이다.
     */
    public RecommendedMiniChallengeListResponse getRecommended(Integer durationDays) {
        List<RecommendedMiniChallenge> presets = (durationDays == null)
                ? repository.findAll()
                : repository.findByDurationDays(durationDays);

        // 노출은 랜덤 순서(0630 확정). 리포지토리가 준 리스트를 직접 섞지 않고 복사본을 섞는다
        // — 파생 쿼리 결과가 불변 리스트일 수 있고, 원본을 건드리지 않는 편이 안전해서.
        List<RecommendedMiniChallenge> shuffled = new ArrayList<>(presets);
        Collections.shuffle(shuffled, random);
        return RecommendedMiniChallengeListResponse.from(shuffled);
    }
}
