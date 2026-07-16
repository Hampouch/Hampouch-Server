package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 조회 계열은 메서드 이름을 문법처럼 해석해 쿼리를 만드는 파생 쿼리(#1 ChallengeDayRepository와 같은 방식).
 * 밑줄(_)은 연관 객체 안으로 들어가는 경로 구분자 — MiniChallenge_Id = miniChallenge 필드를 타고 그 안의 id.
 * 삭제 계열만 벌크 DELETE(@Modifying + @Query)로 직접 정의 — 이유는 각 메서드 주석 참고.
 */
public interface MiniChallengeDayRepository extends JpaRepository<MiniChallengeDay, Long> {

    /** 체크 upsert 분기용 — 그날 행이 이미 있는지(있으면 새로 만들지 않아 checkedAt 보존, §5). */
    boolean existsByMiniChallenge_IdAndCheckDate(Long miniChallengeId, LocalDate checkDate);

    /**
     * 그날(§1) 집계용 — 유저의 미니 여러 건의 체크 이력을 in 절 한 번에 조회.
     * 미니마다 이력을 따로 조회하면 미니 수만큼 쿼리가 나가는 N+1이 되므로 일괄 조회로 묶었다.
     * 조회일(asOf) 이하만 가져오는 이유: 스트릭·checked 계산이 조회일 기준 과거만 보는 as-of 계산이라,
     * 조회일보다 나중 날짜의 체크는 애초에 결과에 영향이 없다(과거 날짜 조회 시 미래 체크가 새어 들지 않게 차단).
     */
    List<MiniChallengeDay> findByMiniChallenge_IdInAndCheckDateLessThanEqual(Collection<Long> miniChallengeIds, LocalDate asOf);

    /**
     * 체크 해제(§5 checked=false) — 행이 없어도 0건 삭제로 끝나 멱등. 반환값 = 지운 행 수.
     *
     * 파생 delete가 아니라 벌크 DELETE 한 문장으로 직접 정의한 이유: 파생 delete는 대상 행을
     * SELECT로 불러온 뒤 건별로 remove한다(엔티티 생명주기 콜백 보장을 위한 스펙 동작) — 이 테이블은
     * 콜백·cascade가 없어 벌크로 지워도 잃는 게 없고 SELECT 없이 DELETE 한 문장으로 끝난다.
     *
     * @Modifying = 이 @Query가 SELECT가 아니라 데이터를 바꾸는 문장이라는 표시(기본 취급은 조회라서
     * 없으면 실행 시점에 터진다). 이걸 붙여야 반환형 int가 "지운 행 수"가 된다 — 멱등 검증이 이 값을 쓴다.
     * 파생 delete엔 불필요 — 이름의 deleteBy로 이미 안다. 벌크는 영속성 컨텍스트를 우회하므로 지울 엔티티가
     * 이미 로딩돼 있으면 메모리와 DB가 어긋날 수 있는데, 여기선 체크 행을 미리 로딩하는 경로가 없어 해당 없음.
     */
    @Modifying
    @Query("delete from MiniChallengeDay d where d.miniChallenge.id = :miniChallengeId and d.checkDate = :checkDate")
    int deleteByMiniChallenge_IdAndCheckDate(@Param("miniChallengeId") Long miniChallengeId, @Param("checkDate") LocalDate checkDate);

    /**
     * 미니 삭제(§4) 시 체크 행 일괄 삭제 — 부모(mini_challenge) 행보다 먼저 지워 FK 제약 위반을 막는다.
     * 파생 delete면 최대 31행(기간 최대치)을 건별 DELETE로 지우게 돼 벌크 한 문장으로 정의(위 메서드와 같은 근거).
     */
    @Modifying
    @Query("delete from MiniChallengeDay d where d.miniChallenge.id = :miniChallengeId")
    int deleteByMiniChallenge_Id(@Param("miniChallengeId") Long miniChallengeId);
}
