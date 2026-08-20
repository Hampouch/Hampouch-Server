package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseDetailRepository expenseDetailRepository;
    private final NoSpendDayRepository noSpendDayRepository;
    private final ExpenseDateLockQuery expenseDateLockQuery;
    private final UserOperationLock userOperationLock;
    private final UserRepository userRepository;
    private final ExpenseImageService expenseImageService;
    private final ExpenseDetailAccess expenseDetailAccess;
    private final Clock clock;

    /** create()가 S3 확인 뒤 createLocked()를 프록시로 재호출하기 위한 자기 참조
     *  @Lazy는 생성자 주입에 복사 안 돼 필드 주입을 쓴다. */
    @Lazy
    @Autowired
    private ExpenseService self;

    /** POST /expenses. imageKey가 있으면 S3 확인을 트랜잭션·사용자 락 밖에서 먼저 끝낸 뒤 self로 createLocked()를 호출. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 클래스 기본 readOnly 트랜잭션을 물려받지 않기 위함
    public ExpenseCreateResponse create(Long userId, ExpenseCreateRequest request) {
        if (request.imageKey() != null) {
            expenseImageService.validateOwnedAndUploaded(userId, request.imageKey());
        }
        return self.createLocked(userId, request);
    }

    /** 사용자 행부터 잠근 뒤 챌린지 기간 행을 잠가 종료 경로와 순서를 맞춘다. imageKey 검증은 create()가 이미 끝냈다. */
    @Transactional
    public ExpenseCreateResponse createLocked(Long userId, ExpenseCreateRequest request) {
        userOperationLock.lock(userId);
        validateExpenseChangeAllowed(userId, request.date());

        User user = userRepository.getReferenceById(userId);
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated()))
            user.updateLastUpdated(request.date());
        String expenseName = (request.name() == null || request.name().isBlank()) ? null : request.name();
        Expense expense = Expense.of(expenseName, request.price(), request.category(), request.emotion(), request.date(), user);
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        Expense saved = expenseRepository.save(expense);
        createDetailIfPresent(saved, request.memo(), request.imageKey());
        noSpendDayRepository.deleteByUser_IdAndRecordDate(userId, request.date());
        return ExpenseCreateResponse.from(saved);
    }

    /**
     * PUT /expenses/no-spend. 챌린지 기간과 무관하게 날짜 기록을 저장한다.
     * 그 날짜에 ACTIVE 지출 또는 '오늘은 안 썼어요' 기록이 있으면 추가 저장 없이 끝낸다.
     */
    @Transactional
    public void recordNoSpend(Long userId, NoSpendRecordRequest request) {
        userOperationLock.lock(userId);
        validateExpenseChangeAllowed(userId, request.date());
        if (expenseRepository.existsByUser_IdAndExpenseDateAndStatus(
                userId, request.date(), ExpenseStatus.ACTIVE)) {
            return;
        }
        if (noSpendDayRepository.existsByUser_IdAndRecordDate(userId, request.date())) {
            return;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated())) {
            user.updateLastUpdated(request.date());
        }
        noSpendDayRepository.save(NoSpendDay.of(user, request.date()));
    }

    /** GET /expenses/{expenseId}. imageUrl은 imageKey가 있을 때만 그때그때 presignGetUrl()로 서명해서 채운다. */
    public ExpenseDetailResponse getDetail(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        ExpenseDetail detail = expenseDetailRepository.findByExpenseId(expenseId).orElse(null);
        String imageUrl = (detail != null && detail.getImageKey() != null)
                ? expenseImageService.presignGetUrl(detail.getImageKey())
                : null;
        return ExpenseDetailResponse.from(expense, detail, imageUrl);
    }

    /**
     * PUT /expenses/{expenseId}. attachCustomTags는 매번 다시 호출 — category/emotion이 ETC 안팎으로 바뀔 수 있어서.
     * 날짜 검증은 기존·새 날짜 둘 다 이른 순으로. 이미지 교체는 전용 API로만
     */
    @Transactional
    public ExpenseCreateResponse update(Long userId, Long expenseId, ExpenseUpdateRequest request) {
        userOperationLock.lock(userId);
        Expense expense = loadOwned(userId, expenseId);
        validateExpenseChangeAllowed(userId, expense.getExpenseDate(), request.date());

        User user = expense.getUser();
        LocalDate oldDate = expense.getExpenseDate();
        String expenseName = (request.name() == null || request.name().isBlank()) ? null : request.name();
        expense.update(expenseName, request.price(), request.category(), request.emotion(), request.date());
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());
        noSpendDayRepository.deleteByUser_IdAndRecordDate(userId, request.date());
        updateMemo(expenseId, expense, request.memo());

        if (user.getLastUpdated() == null || oldDate.equals(user.getLastUpdated())) {
            refreshLastUpdated(user);
        } else if (request.date().isAfter(user.getLastUpdated())) {
            user.updateLastUpdated(request.date());
        }

        return ExpenseCreateResponse.from(expense);
    }

    /** DELETE /expenses/{expenseId} — soft delete. 삭제 대상이 현재 lastUpdated 날짜면 남은 기록 중 최신 날짜로 재계산(없으면 null). */
    @Transactional
    public void delete(Long userId, Long expenseId) {
        userOperationLock.lock(userId);
        Expense expense = loadOwned(userId, expenseId);
        validateExpenseChangeAllowed(userId, expense.getExpenseDate());
        LocalDate deletedDate = expense.getExpenseDate();
        User user = expense.getUser();
        expense.delete();

        if (deletedDate.equals(user.getLastUpdated())) {
            LocalDate latestExpenseDate = expenseRepository
                    .findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(userId, ExpenseStatus.ACTIVE, expenseId)
                    .map(Expense::getExpenseDate)
                    .orElse(null);
            LocalDate latestNoSpendDate = noSpendDayRepository.findTopByUser_IdOrderByRecordDateDesc(userId)
                    .map(NoSpendDay::getRecordDate)
                    .orElse(null);
            user.updateLastUpdated(latestDate(latestExpenseDate, latestNoSpendDate));
        }
    }

    /** lastUpdated를 남은 ACTIVE 지출·무지출 기록 중 최신 날짜로 재계산 — update()가 수정 대상의 기존 날짜==lastUpdated일 때만 호출. 둘 다 없으면 null. */
    private void refreshLastUpdated(User user) {
        LocalDate latestExpenseDate = expenseRepository
                .findTopByUser_IdAndStatusOrderByExpenseDateDesc(user.getId(), ExpenseStatus.ACTIVE)
                .map(Expense::getExpenseDate)
                .orElse(null);
        LocalDate latestNoSpendDate = noSpendDayRepository.findTopByUser_IdOrderByRecordDateDesc(user.getId())
                .map(NoSpendDay::getRecordDate)
                .orElse(null);
        user.updateLastUpdated(latestDate(latestExpenseDate, latestNoSpendDate));
    }

    private LocalDate latestDate(LocalDate first, LocalDate second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    /** GET /expenses/day — 하루 목록 + 합계. 삭제된 지출은 findByUser_IdAndExpenseDateAndStatus에서 이미 제외됨. */
    public ExpenseDayListResponse getDayList(Long userId, LocalDate date) {
        List<Expense> expenses = expenseRepository.findByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        boolean hasRecord = !expenses.isEmpty() || noSpendDayRepository.existsByUser_IdAndRecordDate(userId, date);
        return ExpenseDayListResponse.from(date, expenses, hasRecord);
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

    /** Challenge 도메인이 그 날짜 기록 존재 여부 확인 시 호출 */
    public boolean hasDayRecord(Long userId, LocalDate date) {
        return expenseRepository.existsByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE)
                || noSpendDayRepository.existsByUser_IdAndRecordDate(userId, date);
    }

    /** Challenge 도메인의 일별 예산 초과 판단용 */
    public DaySpending getDaySpending(Long userId, LocalDate date) {
        long totalAmount = expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        return new DaySpending(totalAmount, hasDayRecord(userId, date));
    }

    /**
     * Challenge 도메인의 기간 집계(getCurrent/getCalendar/getResult/getHistory/getRecommendation)용 —
     * [start,end] 각 날짜의 ACTIVE 지출 합계.
     */
    public Map<LocalDate, Long> getDailySpending(Long userId, LocalDate start, LocalDate end) {
        return expenseRepository.sumGroupedByDate(userId, ExpenseStatus.ACTIVE, start, end).stream()
                .collect(Collectors.toMap(ExpenseDailyTotal::date, ExpenseDailyTotal::totalAmount));
    }

    /** GET/PUT/DELETE 공통 조회 진입점. DELETED 지출은 존재해도 EXPENSE_NOT_FOUND로 처리. */
    private Expense loadOwned(Long userId, Long expenseId) {
        Expense expense = expenseRepository.findByIdAndStatus(expenseId, ExpenseStatus.ACTIVE)
                .orElseThrow(() -> new CustomException(ExpenseErrorCode.EXPENSE_NOT_FOUND));
        if (!expense.isOwnedBy(userId)) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_FORBIDDEN);
        }
        return expense;
    }

    /**
     * 지출 변경 가능 날짜 제한 — 정상 종료된 챌린지 기간, 진행 중 챌린지 기간 밖, 미래 날짜는
     * 생성·수정·삭제·무지출 무엇도 못 바꾼다. 진행 중 챌린지가 없으면 과거 날짜는 모두 허용한다.
     */
    private void validateExpenseChangeAllowed(Long userId, LocalDate date) {
        if (expenseDateLockQuery.isExpenseChangeProhibited(userId, date, LocalDate.now(clock))) {
            throw new CustomException(ExpenseErrorCode.EXPENSE_CHALLENGE_CLOSED);
        }
    }

    private void validateExpenseChangeAllowed(Long userId, LocalDate first, LocalDate second) {
        LocalDate earlier = first.isBefore(second) ? first : second;
        LocalDate later = first.isBefore(second) ? second : first;
        validateExpenseChangeAllowed(userId, earlier);
        if (!earlier.equals(later)) {
            validateExpenseChangeAllowed(userId, later);
        }
    }

    /** category/emotion이 ETC일 때만 커스텀 태그를 기록, 그 외엔 명시적으로 null 해제. */
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

    /** memo·imageKey 중 하나라도 있을 때만 ExpenseDetail 생성 */
    private void createDetailIfPresent(Expense expense, String memo, String imageKey) {
        boolean hasMemo = memo != null && !memo.isBlank();
        if (!hasMemo && imageKey == null) {
            return;
        }
        ExpenseDetail detail = ExpenseDetail.of(expense, hasMemo ? memo : null);
        if (imageKey != null) {
            detail.attachImage(imageKey);
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
                                // 동시 insert 경쟁 방지 — ExpenseDetailAccess에 위임
                                expenseDetailAccess.getOrCreate(expense).updateMemo(memo);
                            }
                        }
                );
    }
}
