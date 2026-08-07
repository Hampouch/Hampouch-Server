package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.entity.Expense;
import Hampouch.server.domain.expense.entity.ExpenseCategory;
import Hampouch.server.domain.expense.entity.ExpenseDetail;
import Hampouch.server.domain.expense.entity.ExpenseEmotion;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 지출 5개 우선순위 API(POST/GET/PUT/DELETE /expenses, GET /expenses/day)의 서비스 계층.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 읽기 전용 — 쓰기 메서드만 개별적으로 @Transactional로 덮어씀(ChallengeService와 동일 컨벤션)
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseDetailRepository expenseDetailRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final ExpenseImageService expenseImageService; // create()의 imageKey 검증(HeadObject)에 재사용
    private final Clock clock; //buildSummary()가 dailyAverage 계산 시 오늘까지 경과일수를 구하기 위한 기준
    // 한국 시간 기준으로 통일 된 Bean 활용

    /**
     * POST /expenses.
     * userRepository.getReferenceById()로 실제 SELECT 없이 프록시만 받는다 — userId는 인증 필터를 통과한 값이라
     * 존재를 다시 확인할 필요가 없고, Expense.user는 어차피 FK 값만 있으면 됨
     * (설계 트레이드오프, findById 대비 쿼리 1회 절약).
     */
    @Transactional
    public ExpenseCreateResponse create(Long userId, ExpenseCreateRequest request) {
        validateWithinChallengePeriod(userId, request.date());

        User user = userRepository.getReferenceById(userId);
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated()))
            user.updateLastUpdated(request.date());
        String expenseName = (request.name() == null || request.name().isBlank()) ? null : request.name();
        Expense expense = Expense.of(expenseName, request.price(), request.category(), request.emotion(), request.date(), user);
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());
        expense = expenseRepository.save(expense);

        createDetailIfPresent(expense, request.memo(), request.imageKey());

        return ExpenseCreateResponse.from(expense);
    }

    /** GET /expenses/{expenseId}. */
    public ExpenseDetailResponse getDetail(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        ExpenseDetail detail = expenseDetailRepository.findByExpenseId(expenseId).orElse(null);
        return ExpenseDetailResponse.from(expense, detail);
    }

    /**
     * PUT /expenses/{expenseId}. ExpenseCreateRequest/Response를 그대로 재사용(두 DTO의 자체 Javadoc 참조).
     * attachCustomTags를 매번 다시 호출하는 이유는 Expense.update() Javadoc과 동일 — category/emotion이 ETC에서
     * 다른 값으로(또는 그 반대로) 바뀌었을 수 있어 customCategory/customEmotion을 매번 새 상태 기준으로 재확정해야 함.
     * 날짜 검증(validateWithinChallengePeriod)은 request.date()가 기존 날짜와 실제로 다를 때만 수행
     * — 기존 지출의 이미지 교체는 presign+PATCH /expenses/{expenseId}/photos 전용 흐름으로만
     */
    @Transactional
    public ExpenseCreateResponse update(Long userId, Long expenseId, ExpenseCreateRequest request) {
        Expense expense = loadOwned(userId, expenseId);
        if (!request.date().equals(expense.getExpenseDate())) {
            validateWithinChallengePeriod(userId, request.date());
        }

        User user = expense.getUser();
        LocalDate oldDate = expense.getExpenseDate();
        String expenseName = (request.name() == null || request.name().isBlank()) ? null : request.name();
        expense.update(expenseName, request.price(), request.category(), request.emotion(), request.date());
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());
        updateMemo(expenseId, expense, request.memo());

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
     * ACTIVE 지출이 하나도 없으면 delete()와 동일하게 null로 되돌린다
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
     * ExpenseStatus = DELETED인 Expense의 경우 실제로는 table 상에 존재하지만 사용자에게는 삭제된 것으로 인식되도록
     * CustomException 설정 X
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
     * category/emotion이 ETC일 때만 커스텀 태그 문자열을 기록하고, 그 외엔 명시적으로 null 해제
     * EXPENSE_CUSTOM_*_NAME_DUPLICATED는 내장 enum 라벨(예약어)과의 충돌 전용 에러코드
     */
    private void attachCustomTags(Expense expense, ExpenseCategory category, String customCategoryName,
                                   ExpenseEmotion emotion, String customEmotionName) {
        if (category == ExpenseCategory.ETC && ExpenseCategory.isReservedLabel(customCategoryName)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
        }
        expense.assignCustomCategory(category == ExpenseCategory.ETC ? customCategoryName : null);

        if (emotion == ExpenseEmotion.ETC && ExpenseEmotion.isReservedLabel(customEmotionName)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
        }
        expense.assignCustomEmotion(emotion == ExpenseEmotion.ETC ? customEmotionName : null);
    }

    /**
     * memo·imageKey 중 하나라도 있을 때만 ExpenseDetail을 만든다
     * imageKey가 오면 presign만 거친 값이라 ExpenseImageService.resolveImageUrl()로 S3 HeadObject 검증까지
     * 거쳐야 신뢰할 수 있다(PATCH /expenses/{expenseId}/photos와 동일한 검증 지점 재사용).
     */
    private void createDetailIfPresent(Expense expense, String memo, String imageKey) {
        boolean hasMemo = memo != null && !memo.isBlank();
        if (!hasMemo && imageKey == null) {
            return;
        }
        ExpenseDetail detail = ExpenseDetail.of(expense, hasMemo ? memo : null);
        if (imageKey != null) {
            detail.attachImage(imageKey, expenseImageService.resolveImageUrl(imageKey));
        }
        expenseDetailRepository.save(detail);
    }

    /**
     * update()에서 memo만 반영
     * ExpenseDetail이 없는데 memo도 없으면 아무 것도 만들지 않음
     */
    private void updateMemo(Long expenseId, Expense expense, String memo) {
        boolean hasMemo = memo != null && !memo.isBlank();
        expenseDetailRepository.findByExpenseId(expenseId)
                .ifPresentOrElse(
                        detail -> detail.updateMemo(hasMemo ? memo : null),
                        () -> {
                            if (hasMemo) {
                                expenseDetailRepository.save(ExpenseDetail.of(expense, memo));
                            }
                        }
                );
    }
}
