package Hampouch.server.domain.expense.repository;

import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * ExpenseRepository 파생 쿼리를 H2에 실제 적용해 검증
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class ExpenseRepositoryTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    ExpenseRepository expenseRepository;
    @Autowired
    NoSpendDayRepository noSpendDayRepository;
    @Autowired
    EntityManager em; // 커스텀 태그 왕복 검증에서 1차 캐시를 비우고 DB에서 실제로 다시 읽는지 확인하려면 필요

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocalUser("owner@hampouch.com", "encoded", "포치owner"));
    }

    @Test
    @DisplayName("findByIdAndStatus는 ACTIVE 행만 찾고, DELETED로 바뀐 행은 존재하지 않는 것처럼 취급한다")
    void findByIdAndStatus_excludesDeleted() {
        Expense active = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 5), user));
        Expense deleted = expenseRepository.save(
                Expense.of("배달의민족", 15000, ExpenseCategory.DELIVERY, ExpenseEmotion.COMPENSATION, LocalDate.of(2026, 6, 5), user));
        deleted.delete();
        expenseRepository.flush();

        assertThat(expenseRepository.findByIdAndStatus(active.getId(), ExpenseStatus.ACTIVE)).isPresent();
        assertThat(expenseRepository.findByIdAndStatus(deleted.getId(), ExpenseStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("findByUser_IdAndExpenseDateAndStatus는 그 유저의 그 날짜, ACTIVE 지출만 돌려준다")
    void findByUserAndDateAndStatus_filtersCorrectly() {
        LocalDate target = LocalDate.of(2026, 6, 5);
        Expense onTarget = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        expenseRepository.save(
                Expense.of("다른 날 지출", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target.plusDays(1), user));
        Expense deletedOnTarget = expenseRepository.save(
                Expense.of("삭제된 지출", 2000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        deletedOnTarget.delete();
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findByUser_IdAndExpenseDateAndStatus(
                user.getId(), target, ExpenseStatus.ACTIVE);

        assertThat(result).extracting(Expense::getId).containsExactly(onTarget.getId());
    }

    /**
     * sumPriceByUserIdAndExpenseDateAndStatus/existsByUser_IdAndExpenseDateAndStatus가
     * 실제로 그 유저·그 날짜·ACTIVE만 골라내는지 확인하는 테스트.
     * 다른 유저/다른 날짜/DELETED 3종을 같이 심어서 셋 다 결과에서 빠지는지 함께 검증한다.
     */
    @Test
    @DisplayName("sumPriceByUserIdAndExpenseDateAndStatus는 그 유저·그 날짜·ACTIVE 지출만 합산한다")
    void sumPriceByUserIdAndExpenseDateAndStatus_filtersCorrectly() {
        LocalDate target = LocalDate.of(2026, 6, 5);
        expenseRepository.save(Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        expenseRepository.save(Expense.of("배달의민족", 15000, ExpenseCategory.DELIVERY, ExpenseEmotion.COMPENSATION, target, user));
        expenseRepository.save(Expense.of("다른 날 지출", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target.plusDays(1), user));
        Expense deletedOnTarget = expenseRepository.save(
                Expense.of("삭제된 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        deletedOnTarget.delete();
        User other = userRepository.save(User.createLocalUser("other@hampouch.com", "encoded", "다른유저"));
        expenseRepository.save(Expense.of("다른 유저 지출", 7000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, other));
        expenseRepository.flush();

        int total = expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(user.getId(), target, ExpenseStatus.ACTIVE);

        assertThat(total).isEqualTo(20000);
    }

    @Test
    @DisplayName("sumPriceByUserIdAndExpenseDateAndStatus는 해당 조건의 지출이 하나도 없으면 0을 반환한다(coalesce 확인)")
    void sumPriceByUserIdAndExpenseDateAndStatus_returnsZeroWhenNoneMatch() {
        int total = expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(
                user.getId(), LocalDate.of(2026, 6, 5), ExpenseStatus.ACTIVE);

        assertThat(total).isZero();
    }

    @Test
    @DisplayName("existsByUser_IdAndExpenseDateAndStatus는 그 유저·그 날짜의 ACTIVE 지출이 있을 때만 true를 반환한다")
    void existsByUserIdAndExpenseDateAndStatus_filtersCorrectly() {
        LocalDate target = LocalDate.of(2026, 6, 5);
        Expense onTarget = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, target, user));
        expenseRepository.flush();

        assertThat(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(user.getId(), target, ExpenseStatus.ACTIVE)).isTrue();
        assertThat(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(user.getId(), target.plusDays(1), ExpenseStatus.ACTIVE)).isFalse();

        onTarget.delete();
        expenseRepository.flush();

        assertThat(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(user.getId(), target, ExpenseStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("0원 지출도 카테고리·감정을 가진 일반 지출로 저장된다")
    void zeroPriceExpense_savesWithCategoryAndEmotion() {
        Expense saved = expenseRepository.saveAndFlush(
                Expense.of("무료 음료", 0, ExpenseCategory.CAFE, ExpenseEmotion.CONVENIENCE,
                        LocalDate.of(2026, 6, 5), user));
        em.clear();

        Expense reloaded = expenseRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPrice()).isZero();
        assertThat(reloaded.getCategory()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(reloaded.getEmotion()).isEqualTo(ExpenseEmotion.CONVENIENCE);
    }

    @Test
    @DisplayName("'오늘은 안 썼어요'는 expense 행 없이 유저와 날짜로 저장된다")
    void noSpendDay_savesUserAndDate() {
        LocalDate date = LocalDate.of(2026, 6, 5);

        NoSpendDay saved = noSpendDayRepository.saveAndFlush(NoSpendDay.of(user, date));
        em.clear();

        NoSpendDay reloaded = noSpendDayRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getRecordDate()).isEqualTo(date);
        assertThat(noSpendDayRepository.existsByUser_IdAndRecordDate(user.getId(), date)).isTrue();
        assertThat(expenseRepository.findByUser_IdAndExpenseDateAndStatus(
                user.getId(), date, ExpenseStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("같은 유저의 같은 날짜에 '오늘은 안 썼어요' 기록을 두 번 저장할 수 없다")
    void noSpendDay_rejectsDuplicateUserAndDate() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        noSpendDayRepository.saveAndFlush(NoSpendDay.of(user, date));

        assertThatThrownBy(() -> noSpendDayRepository.saveAndFlush(NoSpendDay.of(user, date)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByUser_IdAndRecordDateBetween은 그 유저의 기간 내 '오늘은 안 썼어요' 기록만 돌려주고 기간 밖·남의 기록은 뺀다 (#101 확장 — 캘린더용)")
    void noSpendDay_findByUserIdAndRecordDateBetween_filtersRangeAndOwner() {
        User other = userRepository.save(User.createLocalUser("other@hampouch.com", "encoded", "포치other"));
        LocalDate inRange = LocalDate.of(2026, 6, 5);
        LocalDate outOfRange = LocalDate.of(2026, 7, 1);
        noSpendDayRepository.saveAndFlush(NoSpendDay.of(user, inRange));
        noSpendDayRepository.saveAndFlush(NoSpendDay.of(user, outOfRange));
        noSpendDayRepository.saveAndFlush(NoSpendDay.of(other, inRange));

        List<NoSpendDay> result = noSpendDayRepository.findByUser_IdAndRecordDateBetween(
                user.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getRecordDate()).isEqualTo(inRange);
    }

    @Test
    @DisplayName("findTopByUser_IdAndStatusAndIdNot...는 삭제 대상(id로 제외)을 뺀 나머지 ACTIVE 지출 중 expenseDate가 가장 최근인 것을 찾는다")
    void findTopByUserAndStatusAndIdNot_findsMostRecentRemainingActiveExpense() {
        Expense older = expenseRepository.save(
                Expense.of("편의점", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 1), user));
        Expense newer = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 5), user));
        // deleting은 셋 중 expenseDate가 가장 최근이지만 삭제 대상 자신이라 id로 제외돼야 함 — 제외가 실제로 동작하는지 검증하는 핵심 포인트
        Expense deleting = expenseRepository.save(
                Expense.of("방금 지운 지출", 1000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 10), user));
        expenseRepository.flush();

        Optional<Expense> result = expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(
                user.getId(), ExpenseStatus.ACTIVE, deleting.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(newer.getId());
    }

    @Test
    @DisplayName("삭제 대상 말고 남은 ACTIVE 지출이 하나도 없으면 빈 값을 반환한다")
    void findTopByUserAndStatusAndIdNot_returnsEmptyWhenNoOtherActiveExpenseLeft() {
        Expense onlyOne = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 5), user));
        expenseRepository.flush();

        Optional<Expense> result = expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(
                user.getId(), ExpenseStatus.ACTIVE, onlyOne.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sumGroupedByDate는 기간 내 ACTIVE 지출만 날짜별로 SUM하고, 기간 밖·DELETED·다른 유저 지출은 제외한다")
    void sumGroupedByDate_groupsActiveExpensesWithinPeriodByDate() {
        LocalDate d1 = LocalDate.of(2026, 6, 8);
        LocalDate d2 = LocalDate.of(2026, 6, 10);
        LocalDate outOfRange = LocalDate.of(2026, 6, 20);
        expenseRepository.save(Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, d1, user));
        expenseRepository.save(Expense.of("편의점", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, d1, user));
        expenseRepository.save(Expense.of("배달의민족", 15000, ExpenseCategory.DELIVERY, ExpenseEmotion.COMPENSATION, d2, user));
        Expense deletedOnD2 = expenseRepository.save(
                Expense.of("삭제된 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, d2, user));
        deletedOnD2.delete();
        expenseRepository.save(Expense.of("기간 밖 지출", 7000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, outOfRange, user));
        User other = userRepository.save(User.createLocalUser("other@hampouch.com", "encoded", "other"));
        expenseRepository.save(Expense.of("남의 지출", 4000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, d1, other));
        expenseRepository.flush();

        List<ExpenseDailyTotal> result = expenseRepository.sumGroupedByDate(
                user.getId(), ExpenseStatus.ACTIVE, LocalDate.of(2026, 6, 7), LocalDate.of(2026, 6, 13));

        assertThat(result).extracting(ExpenseDailyTotal::date, ExpenseDailyTotal::totalAmount)
                .containsExactly(tuple(d1, 8000L), tuple(d2, 15000L));
    }

    @Test
    @DisplayName("sumTodayAndTotalByUsers는 유저별로 today CASE WHEN 합계와 기간 전체 합계를 한 행으로 같이 집계한다")
    void sumTodayAndTotalByUsers_aggregatesPerUserSplitByToday() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 5);
        User userB = userRepository.save(User.createLocalUser("b@hampouch.com", "encoded", "b"));
        expenseRepository.save(Expense.of("어제 지출", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today.minusDays(1), user));
        expenseRepository.save(Expense.of("오늘 지출", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today, user));
        expenseRepository.save(Expense.of("userB 오늘 지출", 2000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today, userB));
        expenseRepository.flush();

        List<BattleParticipantSpending> result = expenseRepository.sumTodayAndTotalByUsers(
                List.of(user.getId(), userB.getId()), start, today, today, ExpenseStatus.ACTIVE);

        assertThat(result)
                .extracting(BattleParticipantSpending::userId, BattleParticipantSpending::todayAmount, BattleParticipantSpending::totalAmount)
                .containsExactlyInAnyOrder(
                        tuple(user.getId(), 5000L, 8000L),
                        tuple(userB.getId(), 2000L, 2000L));
    }

    @Test
    @DisplayName("sumTodayAndTotalByUsers는 기간 밖·DELETED·조회 대상 아닌 유저 지출은 집계에서 빼고, 지출이 없는 유저는 결과 행 자체가 없다")
    void sumTodayAndTotalByUsers_excludesOutOfRangeDeletedAndOtherUsers_andOmitsRowForZeroSpendUser() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 7);
        LocalDate today = LocalDate.of(2026, 8, 5);
        User noSpend = userRepository.save(User.createLocalUser("nospend@hampouch.com", "encoded", "nospend"));
        User notQueried = userRepository.save(User.createLocalUser("notqueried@hampouch.com", "encoded", "notqueried"));
        expenseRepository.save(Expense.of("기간 안 지출", 4000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today, user));
        expenseRepository.save(Expense.of("기간 밖 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, end.plusDays(1), user));
        Expense deleted = expenseRepository.save(
                Expense.of("삭제된 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today, user));
        deleted.delete();
        expenseRepository.save(Expense.of("조회 대상 아닌 유저 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, today, notQueried));
        expenseRepository.flush();

        List<BattleParticipantSpending> result = expenseRepository.sumTodayAndTotalByUsers(
                List.of(user.getId(), noSpend.getId()), start, end, today, ExpenseStatus.ACTIVE);

        assertThat(result)
                .extracting(BattleParticipantSpending::userId, BattleParticipantSpending::totalAmount)
                .containsExactly(tuple(user.getId(), 4000L)); // noSpend는 행 자체가 없음 — 호출부가 0으로 채워야 하는 이유
    }

    @Test
    @DisplayName("findPeriodExpenses는 기간 내 ACTIVE 지출만 최신순으로 돌려주고 기간 밖·DELETED·다른 유저 지출은 제외한다")
    void findPeriodExpenses_filtersAndOrdersByDateDesc() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        Expense older = expenseRepository.save(
                Expense.of("편의점", 3000, ExpenseCategory.CONVENIENCE_STORE, ExpenseEmotion.CONVENIENCE, LocalDate.of(2026, 6, 3), user));
        Expense newer = expenseRepository.save(
                Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 20), user));
        Expense zero = expenseRepository.save(
                Expense.of("무료 음료", 0, ExpenseCategory.CAFE, ExpenseEmotion.CONVENIENCE,
                        LocalDate.of(2026, 6, 21), user));
        expenseRepository.save(
                Expense.of("기간 밖 지출", 7000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 7, 1), user));
        Expense deleted = expenseRepository.save(
                Expense.of("삭제된 지출", 9999, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 10), user));
        deleted.delete();
        User other = userRepository.save(User.createLocalUser("other@hampouch.com", "encoded", "다른유저"));
        expenseRepository.save(
                Expense.of("남의 지출", 4000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 15), other));
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findPeriodExpenses(
                user.getId(), ExpenseStatus.ACTIVE, start, end);

        // containsExactly는 순서까지 본다 — 최신순(expenseDate DESC) 정렬이 실제로 걸렸는지 여기서 같이 검증됨
        assertThat(result).extracting(Expense::getId).containsExactly(zero.getId(), newer.getId(), older.getId());
    }

    @Test
    @DisplayName("findPeriodExpenses는 같은 날짜 안에서 id 내림차순으로 2차 정렬한다")
    void findPeriodExpenses_ordersByIdDescWithinSameDate() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        Expense first = expenseRepository.save(
                Expense.of("아메리카노", 4000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 10), user));
        Expense second = expenseRepository.save(
                Expense.of("라떼", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 10), user));
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findPeriodExpenses(
                user.getId(), ExpenseStatus.ACTIVE, start, end);

        assertThat(result).extracting(Expense::getId).containsExactly(second.getId(), first.getId());
    }

    /**
     * 챌린지 기간 중엔 오늘 이전 날짜로 소급 입력이 가능해서 등록 순서(id)와 expenseDate 순서가
     * 어긋나는 경우가 실제로 생긴다 - 늦게 등록했지만 날짜는 더 이른 지출이 그 예다. expenseDate가
     * 1차 정렬 키이므로 그런 경우에도 최종 순서는 등록 순서가 아니라 날짜를 따라야 한다.
     */
    @Test
    @DisplayName("나중에 등록했어도(id가 더 커도) expenseDate가 더 이르면 뒤로 간다 — 정렬은 등록순이 아니라 날짜순이다")
    void findPeriodExpenses_ordersByExpenseDateNotByRegistrationOrder() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        // 먼저 등록(id 작음) - 날짜는 더 나중(6/20)
        Expense earlyRegisteredLaterDate = expenseRepository.save(
                Expense.of("정상 입력", 4000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 20), user));
        // 나중에 등록(id 큼, 소급 입력) - 날짜는 더 이름(6/5)
        Expense lateRegisteredEarlierDate = expenseRepository.save(
                Expense.of("소급 입력", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, LocalDate.of(2026, 6, 5), user));
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findPeriodExpenses(
                user.getId(), ExpenseStatus.ACTIVE, start, end);

        // id(등록순)로는 반대 순서지만, expenseDate DESC가 이겨야 하므로 6/20짜리가 여전히 먼저다.
        assertThat(result).extracting(Expense::getId)
                .containsExactly(earlyRegisteredLaterDate.getId(), lateRegisteredEarlierDate.getId());
    }

    /**
     * 기간 검증(EXPENSE_ANALYSIS_PERIOD_TOO_LONG)이 양끝 포함 100일 기준이라 조회도 같은 기준이어야 한다.
     * 여기가 어긋나면 에러 메시지는 100일이라고 하는데 실제 집계는 99일치가 되는 식으로 하루가 조용히 빠진다.
     */
    @Test
    @DisplayName("findPeriodExpenses의 기간은 시작일·종료일을 모두 포함한다")
    void findPeriodExpenses_includesBothEnds() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        Expense onStart = expenseRepository.save(
                Expense.of("첫날 지출", 1000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, start, user));
        Expense onEnd = expenseRepository.save(
                Expense.of("마지막날 지출", 2000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, end, user));
        expenseRepository.flush();

        List<Expense> result = expenseRepository.findPeriodExpenses(
                user.getId(), ExpenseStatus.ACTIVE, start, end);

        assertThat(result).extracting(Expense::getId).containsExactly(onEnd.getId(), onStart.getId());
    }

    /**
     * 커스텀 태그 문자열 컬럼이 DB에 실제로 저장·조회되는지 왕복 확인.
     * em.clear()가 핵심이다. 1차 캐시를 비우지 않으면 저장할 때 올려둔 인스턴스가 그대로 나와
     * 컬럼 매핑이 깨져도 이 테스트가 통과해버려 검증이 무의미해진다.
     */
    @Test
    @DisplayName("커스텀 태그 문자열은 저장 후 DB에서 다시 읽어도 그대로 유지된다")
    void findPeriodExpenses_roundTripsCustomTagColumns() {
        LocalDate date = LocalDate.of(2026, 6, 8);
        Expense expense = Expense.of("무인카페", 4000, ExpenseCategory.ETC, ExpenseEmotion.ETC, date, user);
        expense.assignCustomCategory("스터디카페");
        expense.assignCustomEmotion("억울해서");
        expenseRepository.save(expense);
        em.flush();
        em.clear();

        List<Expense> result = expenseRepository.findPeriodExpenses(
                user.getId(), ExpenseStatus.ACTIVE, date, date);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCustomCategory()).isEqualTo("스터디카페");
        assertThat(result.getFirst().getCustomEmotion()).isEqualTo("억울해서");
    }

    /**
     * name 컬럼을 nullable로 바꾼 게 실제 스키마(H2, ddl-auto)에도 반영됐는지 확인.
     * Mockito 테스트(ExpenseServiceTest)는 실제 제약조건을 안 타서 여기서만 잡을 수 있는 지점 —
     * @Column(nullable=false)가 실수로 남아있었다면 이 테스트가 DataIntegrityViolationException으로 실패한다.
     */
    @Test
    @DisplayName("name이 null인 지출도 저장·조회가 그대로 된다 (name nullable 전환 확인)")
    void findPeriodExpenses_allowsNullName() {
        LocalDate date = LocalDate.of(2026, 6, 8);
        Expense expense = Expense.of(null, 3000, ExpenseCategory.ETC, ExpenseEmotion.ETC, date, user);
        expenseRepository.save(expense);
        em.flush();
        em.clear();

        List<Expense> result = expenseRepository.findPeriodExpenses(user.getId(), ExpenseStatus.ACTIVE, date, date);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getName()).isNull();
    }
}
