package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * status를 항상 조건에 포함시키는 이유: Expense는 soft delete 대상이라 findById만 쓰면
 * 이미 삭제된 행이 그대로 조회돼 GET/PUT/DELETE가 EXPENSE_NOT_FOUND를 내야 할 상황에서 실수로
 * 삭제된 데이터를 돌려주게 된다 — Challenge에는 이 문제가 없어 loadOwned가 findById를
 * 그대로 쓰지만, Expense는 반드시 findByIdAndStatus로 대체해야 함.
 */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 단건 조회(GET/PUT/DELETE 공통 진입점) — service의 loadOwned가 이걸로 조회 후 isOwnedBy로 소유권 검증. */
    Optional<Expense> findByIdAndStatus(Long id, ExpenseStatus status);

    /**
     * GET /expenses/day — 특정 유저의 특정 날짜 지출 목록, 삭제분 제외.
     * ChallengeDayRepository의 언더스코어 경로 컨벤션과 동일(User_Id = user 연관관계를 타고 들어간 id).
     */
    List<Expense> findByUser_IdAndExpenseDateAndStatus(Long userId, LocalDate expenseDate, ExpenseStatus status);

    /**
     * GET /expenses/summary/week, /summary/month 공용 — 기간 내 유저의 ACTIVE 지출을 날짜별로 SUM해서 가져온다.
     * 건수가 하루 단위(findByUser_IdAndExpenseDateAndStatus)보다 커질 수 있어 SUM을 DB에 위임
     */
    @Query("""
            SELECT new Hampouch.server.domain.expense.repository.ExpenseDailyTotal(e.expenseDate, SUM(e.price))
            FROM Expense e
            WHERE e.user.id = :userId AND e.status = :status AND e.expenseDate BETWEEN :start AND :end
            GROUP BY e.expenseDate
            ORDER BY e.expenseDate
            """)
    List<ExpenseDailyTotal> sumGroupedByDate(@Param("userId") Long userId, @Param("status") ExpenseStatus status,
                                              @Param("start") LocalDate start, @Param("end") LocalDate end);
     /* Spring Data 파생 쿼리는 SUM 같은 집계를 지원하지 않아
     * sumPriceByUserIdAndExpenseDateAndStatus를 @Query로 직접 작성하고, coalesce로 감싸 그 날짜에
     * 지출이 하나도 없을 때도 null 대신 0을 반환하게 한다.
     */

    @Query("select coalesce(sum(e.price), 0) from Expense e "
            + "where e.user.id = :userId and e.expenseDate = :date and e.status = :status")
    int sumPriceByUserIdAndExpenseDateAndStatus(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("status") ExpenseStatus status);

    /**
     * ExpenseService.getDaySpending()에서 DaySpending.hasRecord를 채우는 용도 — 합계(sum)만으로는
     * 그 날짜에 기록 자체가 없음과 그 날짜 기록의 합계가 0원임을 구분할 수 없어 별도 존재 확인 쿼리로 둔다.
     */
    boolean existsByUser_IdAndExpenseDateAndStatus(Long userId, LocalDate expenseDate, ExpenseStatus status);

    /**
     * ExpenseService.delete()가 User.lastUpdated를 되돌릴 기준을 찾는 용도
     * 지금 삭제 중인 지출을 뺀 나머지 지출 중 expenseDate가 가장 최근인 1건을 찾는다.
     * -> 3일 이상 지출 기록이 비면 무효화라는 규칙은 지출 발생 날짜를 기준으로 하므로, 언제 등록됐는지는 무관
     * 지운 지출이 유일한 지출이었다면 서비스가 User.lastUpdated를 User.createdAt(계정 생성일)로 대신 되돌린다.
     */
    Optional<Expense> findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(Long userId, ExpenseStatus status, Long id);

    /**
     * ExpenseService.refreshLastUpdated()가 expense를 update() 이후 User.lastUpdated를 다시 계산할 때 쓰는 용도.
     * delete()가 쓰는 AndIdNot 버전과 달리, 방금 생성/수정된 지출도 그대로 포함해서 계산해야 하므로 제외 조건이 없다.
     */
    Optional<Expense> findTopByUser_IdAndStatusOrderByExpenseDateDesc(Long userId, ExpenseStatus status);

    /**
     * GET /expenses/analysis 및 자세히 보기 2종(카테고리별/이유별) 공용 - 기간 내 유저의 ACTIVE 지출 내역을 그대로 가져온다.
     * sumGroupedByDate처럼 DB에서 집계하지 않고 행을 꺼내는 이유:
     * 카테고리별/이유별/요일별 집계와 자세히 보기의 items가 전부 같은 기간의 같은 행에서 나온다. 한 번 꺼내
     * Java에서 나누면 쿼리 1번으로 엔드포인트 3개를 덮지만, GROUP BY 쿼리를 따로 두면 items 때문에 어차피
     * 이 조회가 또 필요해져 쿼리만 늘어난다. 특히 요일 집계는 JPQL 표준에 요일 추출 함수가 없어 DB에 맡기면
     * dialect 종속 함수를 써야 하고 요일 번호 체계(일요일이 1인지 7인지)까지 DB에 의존하게 된다.
     *
     * 행을 전부 꺼내도 되는 근거는 기간 상한 100일(EXPENSE_ANALYSIS_PERIOD_TOO_LONG)이다.
     * 즉 그 검증 규칙이 곧 이 조회의 크기 상한이므로, 상한을 올릴 땐 여기 부하도 같이 봐야 한다.
     *
     * LEFT JOIN FETCH가 필수인 이유: customCategory/customEmotion은 둘 다 LAZY라 DTO 변환이 getName()을
     * 건드리는 순간 행 수만큼 추가 쿼리가 나간다. 하루치만 다루는 /expenses/day에서는 티가 안 났지만 100일
     * 기간에서는 그대로 N+1이 된다. 둘 다 @ManyToOne(단일 값)이라 컬렉션을 동시에 fetch할 때 생기는
     * MultipleBagFetchException 문제는 해당 없다.
     *
     * 정렬은 최신순(자세히 보기 목록이 최근 지출부터 보여줌). 같은 날 여러 건일 때 순서가 흔들리지 않도록
     * id를 2차 정렬 키로 둔다 - 정렬 키가 날짜뿐이면 DB가 동률 행의 순서를 보장하지 않아 같은 요청이
     * 매번 다른 순서로 나갈 수 있다.
     */
    @Query("""
            SELECT e FROM Expense e
            LEFT JOIN FETCH e.customCategory
            LEFT JOIN FETCH e.customEmotion
            WHERE e.user.id = :userId AND e.status = :status
              AND e.expenseDate BETWEEN :start AND :end
            ORDER BY e.expenseDate DESC, e.id DESC
            """)
    List<Expense> findPeriodWithCustomTags(@Param("userId") Long userId, @Param("status") ExpenseStatus status,
                                           @Param("start") LocalDate start, @Param("end") LocalDate end);
}
