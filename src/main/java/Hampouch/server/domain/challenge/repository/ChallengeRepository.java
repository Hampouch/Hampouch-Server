package Hampouch.server.domain.challenge.repository;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 챌린지 저장소. findById·save 같은 CRUD 기본기는 이 파일에 선언이 없어도 쓸 수 있다 —
 * 부모 인터페이스 사슬(JpaRepository ← ListCrudRepository ← CrudRepository)에 이미 선언돼
 * 있어 상속으로 물려받기 때문. 구현 클래스도 우리가 안 만든다 — 스프링 데이터가 부팅 때
 * 이 인터페이스의 프록시 구현체를 만들어 빈으로 등록한다(CRUD 기본기는 SimpleJpaRepository 구현).
 * 아래 파생 쿼리들도 선언만 하면 구현은 스프링이 메서드 이름을 해석해 만든다 —
 * 기본기와 파생 쿼리는 선언 출처만 다를 뿐(부모 vs 이 파일) 구현 주체는 둘 다 스프링이다.
 *
 * findById가 0 또는 1건인 근거는 DB 기본 키 제약(같은 id 두 행은 존재 불가) —
 * 도메인 규칙(코드 게이트)에 기대는 findInProgress의 "0 또는 1"보다 강한 보장이다.
 */
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    /**
     * 동시 진행 1개 가정 — 생성 시 중복 체크용 파생 쿼리.
     * status(IN_PROGRESS)까지 보는 이유: 유저의 끝난 챌린지(SUCCESS/FAIL)도 DB에 남으므로,
     * userId만 보면 과거에 챌린지 한 유저는 새로 못 만듦. "진행 중"만 세야 함.
     * 직접 호출하지 말고 아래 existsInProgress(상태 고정판)를 쓸 것.
     */
    boolean existsByUserIdAndStatus(Long userId, ChallengeStatus status);

    /**
     * 진행 중 챌린지 1건 조회용 파생 쿼리 (홈/현황, 만료 lazy 확정).
     *
     * userId+status 조합이 "딱 1건"인 건 쿼리가 보장하는 게 아니라 도메인 규칙이 보장한다 —
     * 동시 진행은 1개뿐(create의 existsInProgress 게이트가 409로 차단)이라 IN_PROGRESS는
     * 유저당 최대 1행. 반환 타입 Optional은 그 규칙을 믿고 "0 아니면 1"을 기대한다는 선언이고,
     * 실제 2행 이상이 걸리면 Spring Data가 IncorrectResultSizeDataAccessException을 던진다(500).
     * 그래서 이 쿼리는 IN_PROGRESS 전용 — SUCCESS/FAIL은 유저당 여러 개 쌓이는 게 정상이라
     * 그런 조회는 List를 반환하는 아래 히스토리 쿼리 몫이다.
     * 직접 호출하지 말고 아래 findInProgress(상태 고정판)를 쓸 것.
     */
    Optional<Challenge> findByUserIdAndStatus(Long userId, ChallengeStatus status);

    /**
     * 진행 중 챌린지 존재 여부 — create의 동시 진행 1개 게이트 전용.
     * 파생 쿼리 이름 문법에는 값(IN_PROGRESS)을 박을 자리가 없어(이름은 속성만 정하고 값은 파라미터)
     * default 메서드로 감싸 상태를 고정했다 — 호출부가 다른 상태를 넘길 통로를 구조로 막는다.
     */
    default boolean existsInProgress(Long userId) {
        return existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    /**
     * 진행 중 챌린지 조회 — 동시 진행 1개 규칙 위에서 0 또는 1건(규칙 근거는 위 파생 쿼리 주석).
     * IN_PROGRESS 전용이라는 계약을 주석이 아니라 시그니처로 지키기 위한 상태 고정판 —
     * 서비스는 이것만 호출한다.
     */
    default Optional<Challenge> findInProgress(Long userId) {
        return findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS);
    }

    /**
     * 지난 챌린지 리스트(#4) — 종료된 것만, 최근 종료(endDate 내림차순)가 먼저.
     * "IN_PROGRESS가 아닌 전부"가 아니라 보여줄 상태를 In(SUCCESS, FAIL)으로 명시하는 이유:
     * VOID 같은 상태가 추가됐을 때 Not 조건은 그 상태를 자동으로 리스트에 흘려보낸다.
     * 미입력 자동 취소 VOID는 지난 기록에서 제외하므로 보여줄 상태만 명시한다.
     * endDate가 같으면 id 내림차순(나중에 만든 것 먼저) — 정렬이 매번 같도록 붙인 보조 기준(자체 결정).
     *
     * 이름 읽는 법(조건부): findBy 뒤 UserId(키워드 없음 = 같음 비교) And StatusIn(컬렉션 안의 값 중 하나, SQL IN)
     * → WHERE user_id = ? AND status IN (...). 메서드 파라미터는 이 조건 순서대로 대응 — In 조건이라
     * 두 번째 파라미터가 단일 값이 아닌 Collection이다. OrderBy 키워드를 만나는 지점에서 조건부가 끝난다.
     * 이름 읽는 법(정렬부): OrderBy 뒤는 "속성+방향" 쌍의 나열 — EndDateDesc, IdDesc
     * → SQL의 ORDER BY end_date DESC, id DESC. 방향을 생략하면 Asc가 기본이라 Desc는 매번 붙여야 한다.
     * 두 키가 각각 따로 전체를 정렬하는 게 아니라, 둘째 키(id)는 첫 키(endDate)가 같은 행들 사이에서만 순서를 정한다.
     */
    List<Challenge> findByUserIdAndStatusInOrderByEndDateDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);

    /**
     * 직전 종료 챌린지 1건 — 휴식기 홈의 keptRecords(#8) 재료.
     * findFirst = 정렬 결과의 맨 앞 1건만(SQL LIMIT 1) — List로 다 가져와 첫 원소를 집는 게 아니라 쿼리가 1건으로 끝난다.
     *
     * "직전"의 정렬이 히스토리의 endDate가 아니라 생성 시각(createdAt)인 이유: 중도 포기 챌린지는 endDate가
     * 원래 목표 기간 그대로 보존돼 미래일 수 있어서, endDate 내림차순은 "예전에 포기한 긴 챌린지"를
     * 나중에 실제로 끝낸 챌린지보다 최근으로 오판한다(예: 30일짜리 이틀 만에 포기 → 그 뒤 7일짜리 완주).
     * 동시 진행 1개 규칙이라 챌린지는 순차로만 생기고, 종료된 것들 중에선 마지막에 만든 것이 곧
     * 마지막에 끝난 것 — 생성순이 종료순을 정확히 대신한다. 생성순은 의미 그대로인 createdAt이 지고
     * id는 같은 시각일 때의 보조 기준만 맡는다(id 필드 주석의 "순서 논리에 쓰지 말 것" 규칙 + 히스토리
     * 보조 정렬과 동일한 역할 분담).
     */
    Optional<Challenge> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(Long userId, Collection<ChallengeStatus> statuses);
}
