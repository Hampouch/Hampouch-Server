package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 꺾쇠 첫째 = 이 리포지토리가 다루는 엔티티 타입, 둘째 = 그 엔티티 @Id 필드의 타입(Long id → Long).
// 둘째 타입이 findById·existsById·deleteById 등 상속 메서드의 인자 타입으로 흘러 들어간다.
// 제네릭 자리라 원시형 long은 못 쓰고 래퍼 Long이어야 한다.
public interface RecommendedMiniChallengeRepository extends JpaRepository<RecommendedMiniChallenge, Long> {

    /**
     * 기간 탭 필터(API명세_미니챌린지.md §2의 durationDays 파라미터) — 메서드 이름 파생 쿼리.
     * 시그니처는 개발자가 정하고 스프링 데이터가 부트 시점에 검증한다: 인자는 이름 속 조건
     * (DurationDays)과 순서대로 짝지어지므로 엔티티 필드 타입(int)을 따라야 하고, 리턴은
     * 지원 목록(List·Optional·단건·Page 등) 중 결과 개수 기대에 맞는 걸 고른다 —
     * 여기는 0..N 매칭이라 List(못 찾으면 null이 아니라 빈 리스트).
     * 화이트리스트(1·3·7·14·31) 밖 값이 와도 매칭 행이 없어 자연스럽게 빈 목록이 된다.
     */
    List<RecommendedMiniChallenge> findByDurationDays(int durationDays);
}
