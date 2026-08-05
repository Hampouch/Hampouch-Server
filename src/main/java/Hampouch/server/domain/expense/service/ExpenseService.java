package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.*;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 지출 생성·조회·수정·삭제와 '오늘은 안 썼어요' 날짜 기록 API의 서비스 계층.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 읽기 전용 — 쓰기 메서드만 개별적으로 @Transactional로 덮어씀(ChallengeService와 동일 컨벤션)
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final NoSpendDayRepository noSpendDayRepository;
    private final CustomCategoryRepository customCategoryRepository;
    private final CustomEmotionRepository customEmotionRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final Clock clock; //buildSummary()가 dailyAverage 계산 시 오늘까지 경과일수를 구하기 위한 기준
    // 한국 시간 기준으로 통일 된 Bean 활용


    /**
     * POST /expenses.
     * userRepository.getReferenceById()로 실제 SELECT 없이 프록시만 받는다 — userId는 인증 필터를 통과한 값이라
     * 존재를 다시 확인할 필요가 없고, Expense.user/CustomCategory.user/CustomEmotion.user는 어차피 FK 값만 있으면 됨
     * (설계 트레이드오프, findById 대비 쿼리 1회 절약).
     */
    @Transactional
    public ExpenseCreateResponse create(Long userId, ExpenseCreateRequest request) {
        validateWithinChallengePeriod(userId, request.date());

        User user = userRepository.getReferenceById(userId);
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated()))
            user.updateLastUpdated(request.date());
        Expense expense = Expense.of(request.name(), request.price(), request.category(), request.emotion(), request.date(), user);
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        Expense saved = expenseRepository.save(expense);
        noSpendDayRepository.deleteByUser_IdAndRecordDate(userId, request.date());
        return ExpenseCreateResponse.from(saved);
    }

    /**
     * PUT /expenses/no-spend. 챌린지 기간과 무관하게 날짜 기록을 저장한다.
     * 그 날짜에 ACTIVE 지출 또는 '오늘은 안 썼어요' 기록이 있으면 추가 저장 없이 끝낸다.
     */
    @Transactional
    public void recordNoSpend(Long userId, NoSpendRecordRequest request) {
        if (expenseRepository.existsByUser_IdAndExpenseDateAndStatus(
                userId, request.date(), ExpenseStatus.ACTIVE)) {
            return;
        }
        if (noSpendDayRepository.existsByUser_IdAndRecordDate(userId, request.date())) {
            return;
        }

        User user = userRepository.getReferenceById(userId);
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated())) {
            user.updateLastUpdated(request.date());
        }
        noSpendDayRepository.save(NoSpendDay.of(user, request.date()));
    }

    /** GET /expenses/{expenseId}. */
    public ExpenseDetailResponse getDetail(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        return ExpenseDetailResponse.from(expense);
    }

    /**
     * PUT /expenses/{expenseId}. ExpenseCreateRequest/Response를 그대로 재사용(두 DTO의 자체 Javadoc 참조).
     * attachCustomTags를 매번 다시 호출하는 이유는 Expense.update() Javadoc과 동일 — category/emotion이 ETC에서
     * 다른 값으로(또는 그 반대로) 바뀌었을 수 있어 customCategory/customEmotion을 매번 새 상태 기준으로 재확정해야 함.
     * 날짜 검증(validateWithinChallengePeriod)은 request.date()가 기존 날짜와 실제로 다를 때만 수행
     */
    @Transactional
    public ExpenseCreateResponse update(Long userId, Long expenseId, ExpenseCreateRequest request) {
        Expense expense = loadOwned(userId, expenseId);
        if (!request.date().equals(expense.getExpenseDate())) {
            validateWithinChallengePeriod(userId, request.date());
        }

        User user = expense.getUser();
        LocalDate oldDate = expense.getExpenseDate();
        expense.update(request.name(), request.price(), request.category(), request.emotion(), request.date());
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        if (user.getLastUpdated() == null || oldDate.equals(user.getLastUpdated())) {
            refreshLastUpdated(user);
        } else if (request.date().isAfter(user.getLastUpdated())) {
            user.updateLastUpdated(request.date());
        }

        return ExpenseCreateResponse.from(expense);
    }

    /**
     * DELETE /expenses/{expenseId} — 소프트 삭제(Expense.delete()), 물리 삭제 아님.
     * 삭제 대상의 expenseDate가 현재 User.lastUpdated에 해당하는 expense일 수 있으므로,
     * 그 경우에만 남은 ACTIVE 지출 중 가장 최근 날짜로 재계산
     * 남은 ACTIVE 지출이 하나도 없으면 null로 되돌린다
     */
    @Transactional
    public void delete(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        LocalDate deletedDate = expense.getExpenseDate();
        User user = expense.getUser();
        expense.delete();

        if (deletedDate.equals(user.getLastUpdated())) {
            LocalDate revertedLastUpdated = expenseRepository
                    .findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(userId, ExpenseStatus.ACTIVE, expenseId)
                    .map(Expense::getExpenseDate)
                    .orElse(null);
            user.updateLastUpdated(revertedLastUpdated);
        }
    }

    /**
     * User.lastUpdated를 ACTIVE 지출 중 가장 최근 expenseDate로 다시 계산해서 반영.
     * update()가 수정 대상 지출의 기존 날짜 == 현재 lastUpdated인 경우에만 호출
     * ACTIVE 지출이 하나도 없으면 delete()와 동일하게 null로 되돌린다(계정 생성일로 대체하면 create()의
     * null 초기값과 다시 모순이 생김).
     */
    private void refreshLastUpdated(User user) {
        LocalDate latest = expenseRepository
                .findTopByUser_IdAndStatusOrderByExpenseDateDesc(user.getId(), ExpenseStatus.ACTIVE)
                .map(Expense::getExpenseDate)
                .orElse(null);
        user.updateLastUpdated(latest);
    }

    /** GET /expenses/day — 하루 목록 + 합계. 삭제된 지출은 findByUser_IdAndExpenseDateAndStatus에서 이미 제외됨. */
    public ExpenseDayListResponse getDayList(Long userId, LocalDate date) {
        List<Expense> expenses = expenseRepository.findByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        return ExpenseDayListResponse.from(date, expenses);
    }

    /** GET /expenses/summary/week — standardDate가 속한 주(일~토)의 합계·일별 내역. */
    public ExpenseSummaryResponse getWeekSummary(Long userId, LocalDate standardDate) {
        int daysSinceSunday = standardDate.getDayOfWeek().getValue() % 7; // MONDAY=1..SATURDAY=6, SUNDAY=7→0
        LocalDate periodStart = standardDate.minusDays(daysSinceSunday);
        LocalDate periodEnd = periodStart.plusDays(6);
        return buildSummary(userId, periodStart, periodEnd);
    }

    /** GET /expenses/summary/month — standardMonth 해당 월의 합계·일별 내역. */
    public ExpenseSummaryResponse getMonthSummary(Long userId, YearMonth standardMonth) {
        LocalDate periodStart = standardMonth.atDay(1);
        LocalDate periodEnd = standardMonth.atEndOfMonth();
        return buildSummary(userId, periodStart, periodEnd);
    }

    /** week/month 공용 조립 — 날짜별 합계 조회 후 DTO에 위임(오늘 날짜는 dailyAverage의 경과일수 계산에 필요). */
    private ExpenseSummaryResponse buildSummary(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        List<ExpenseDailyTotal> dailyTotals = expenseRepository.sumGroupedByDate(
                userId, ExpenseStatus.ACTIVE, periodStart, periodEnd);
        return ExpenseSummaryResponse.of(periodStart, periodEnd, dailyTotals, LocalDate.now(clock));
    }
  
    /**
     * Challenge 도메인이 일별 예산 초과 여부를 판단할 때 호출
     * 특정 유저의 특정 날짜 ACTIVE 지출 합계(원)와 그 날짜에 기록 자체가 있었는지를 반환
     */
    public DaySpending getDaySpending(Long userId, LocalDate date) {
        int totalAmount = expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        boolean hasRecord = expenseRepository.existsByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        return new DaySpending(totalAmount, hasRecord);
    }

    /** GET/PUT/DELETE 공통 조회 진입점 — ChallengeService.loadOwned()와 동일한 이름/구조.
     *  ExpenseStatus = DELETED인 Expense의 경우 실제로는 table 상에 존재하지만 사용자에게는 삭제된 것으로 인식되도록
     *  CustomException 설정 X
     */
    private Expense loadOwned(Long userId, Long expenseId) {
        Expense expense = expenseRepository.findByIdAndStatus(expenseId, ExpenseStatus.ACTIVE)
                .orElseThrow(() -> new CustomException(ExpenseErrorCode.EXPENSE_NOT_FOUND));
        if (!expense.isOwnedBy(userId)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_FORBIDDEN);
        }
        return expense;
    }

    /**
     * 진행 중인 메인 챌린지 기간 검증.
     * 챌린지가 있으면 그 기간(startDate~endDate) 밖 날짜를 막는다.
     * 챌린지가 없으면 검증하지 않는다 — ChallengeService는 종료된 challenge의 status가
     * 바로 변화하지 않음. challenge가 없을 때 지출 입력 일자 제한은 이슈 #50에서 별도로 처리
     */
    private void validateWithinChallengePeriod(Long userId, LocalDate date) {
        challengeRepository.findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS)
                .ifPresent(challenge -> {
                    if (date.isBefore(challenge.getStartDate()) || date.isAfter(challenge.getEndDate())) {
                        throw new CustomException(ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
                    }
                });
    }

    /**
     * category/emotion이 ETC일 때만 find-or-create로 커스텀 태그를 연결하고, 그 외엔 명시적으로 null 해제
     * expense.getUser()로 이미 갖고 있는 연관관계를 재사용 — LAZY 프록시라도 FK 값(id)은 이미 알고 있어 추가 조회 없이
     * CustomCategory.of()/CustomEmotion.of()에 그대로 넘길 수 있다.
     * 내장 enum 라벨과의 중복 검사는 CustomCategory/CustomEmotion의 (user_id, name) 유니크 제약과는 별개 책임
     * 동시 요청 발생 시 DataIntegrityViolationException throw(409 Error)
     */
    private void attachCustomTags(Expense expense, ExpenseCategory category, String customCategoryName,
                                   ExpenseEmotion emotion, String customEmotionName) {
        Long userId = expense.getUser().getId();

        if (category == ExpenseCategory.ETC) {
            if (ExpenseCategory.isReservedLabel(customCategoryName)) {
                throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
            }
            CustomCategory customCategory;
            try {
                customCategory = customCategoryRepository.findByUser_IdAndName(userId, customCategoryName)
                        .orElseGet(() -> customCategoryRepository.save(CustomCategory.of(expense.getUser(), customCategoryName)));
            } catch (DataIntegrityViolationException e) {
                throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
            }
            expense.assignCustomCategory(customCategory);
        } else {
            expense.assignCustomCategory(null);
        }

        if (emotion == ExpenseEmotion.ETC) {
            if (ExpenseEmotion.isReservedLabel(customEmotionName)) {
                throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
            }
            CustomEmotion customEmotion;
            try {
                customEmotion = customEmotionRepository.findByUser_IdAndName(userId, customEmotionName)
                        .orElseGet(() -> customEmotionRepository.save(CustomEmotion.of(expense.getUser(), customEmotionName)));
            } catch (DataIntegrityViolationException e) {
                throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
            }
            expense.assignCustomEmotion(customEmotion);
        } else {
            expense.assignCustomEmotion(null);
        }
    }
}
