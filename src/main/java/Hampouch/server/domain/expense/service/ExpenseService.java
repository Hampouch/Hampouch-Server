package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.expense.dto.*;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseDetailRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.expense.repository.NoSpendDayRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
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
    private final ExpenseDetailRepository expenseDetailRepository;
    private final NoSpendDayRepository noSpendDayRepository;
    private final ExpenseRecordLock expenseRecordLock;
    private final ExpenseDateLockQuery expenseDateLockQuery;
    private final UserRepository userRepository;
    private final ExpenseImageService expenseImageService; // create()의 imageKey 검증(HeadObject)에 재사용
    private final ExpenseDetailAccess expenseDetailAccess; // updateMemo()의 get-or-create 동시성 경쟁 방지(#8)
    private final Clock clock; //buildSummary()가 dailyAverage 계산 시 오늘까지 경과일수를 구하기 위한 기준
    // 한국 시간 기준으로 통일 된 Bean 활용

    /** POST /expenses. 사용자 행 잠금 뒤 같은 영속성 컨텍스트의 User를 지출 연관관계와 lastUpdated 갱신에 사용한다. */
    @Transactional
    public ExpenseCreateResponse create(Long userId, ExpenseCreateRequest request) {
        expenseRecordLock.lockUser(userId);
        validateExpenseChangeAllowed(userId, request.date());

        User user = userRepository.getReferenceById(userId);
        if (user.getLastUpdated() == null || request.date().isAfter(user.getLastUpdated()))
            user.updateLastUpdated(request.date());
        String expenseName = (request.name() == null || request.name().isBlank()) ? null : request.name();
        Expense expense = Expense.of(expenseName, request.price(), request.category(), request.emotion(), request.date(), user);
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        Expense saved = expenseRepository.save(expense);
        createDetailIfPresent(userId, saved, request.memo(), request.imageKey());
        noSpendDayRepository.deleteByUser_IdAndRecordDate(userId, request.date());
        return ExpenseCreateResponse.from(saved);
    }

    /**
     * PUT /expenses/no-spend. 챌린지 기간과 무관하게 날짜 기록을 저장한다.
     * 그 날짜에 ACTIVE 지출 또는 '오늘은 안 썼어요' 기록이 있으면 추가 저장 없이 끝낸다.
     */
    @Transactional
    public void recordNoSpend(Long userId, NoSpendRecordRequest request) {
        expenseRecordLock.lockUser(userId);
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

    /**
     * GET /expenses/{expenseId}.
     * imageUrl은 DB에 저장된 값이 아니라 imageKey가 있을 때만 그때그때 presignGetUrl()로 새로 서명해서 채운다(#6).
     */
    public ExpenseDetailResponse getDetail(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        ExpenseDetail detail = expenseDetailRepository.findByExpenseId(expenseId).orElse(null);
        String imageUrl = (detail != null && detail.getImageKey() != null)
                ? expenseImageService.presignGetUrl(detail.getImageKey())
                : null;
        return ExpenseDetailResponse.from(expense, detail, imageUrl);
    }

    /**
     * PUT /expenses/{expenseId}. ExpenseCreateRequest/Response를 그대로 재사용(두 DTO의 자체 Javadoc 참조).
     * attachCustomTags를 매번 다시 호출하는 이유는 Expense.update() Javadoc과 동일 — category/emotion이 ETC에서
     * 다른 값으로(또는 그 반대로) 바뀌었을 수 있어 customCategory/customEmotion을 매번 새 상태 기준으로 재확정해야 함.
     * 날짜 검증은 기존 날짜·새 날짜 둘 다 대상으로 수행(락 순서를 보장하기 위해 항상 이른 날짜부터) —
     * 기존 지출의 이미지 교체는 presign+PATCH /expenses/{expenseId}/photos 전용 흐름으로만
     */
    @Transactional
    public ExpenseCreateResponse update(Long userId, Long expenseId, ExpenseUpdateRequest request) {
        expenseRecordLock.lockUser(userId);
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

    /**
     * DELETE /expenses/{expenseId} — 소프트 삭제(Expense.delete()), 물리 삭제 아님.
     * 삭제 대상의 expenseDate가 현재 User.lastUpdated에 해당하는 expense일 수 있으므로,
     * 그 경우에만 남은 ACTIVE 지출과 무지출 날짜 기록 중 가장 최근 날짜로 재계산한다.
     * 둘 다 없으면 null로 되돌린다.
     */
    @Transactional
    public void delete(Long userId, Long expenseId) {
        expenseRecordLock.lockUser(userId);
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

    /**
     * User.lastUpdated를 ACTIVE 지출과 무지출 날짜 기록 중 가장 최근 날짜로 다시 계산해서 반영.
     * update()가 수정 대상 지출의 기존 날짜 == 현재 lastUpdated인 경우에만 호출
     * 두 기록이 모두 없으면 delete()와 동일하게 null로 되돌린다(계정 생성일로 대체하면 create()의
     * null 초기값과 다시 모순이 생김).
     */
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

    /**
     * Challenge 도메인이 그 날짜에 기록이 있었는지만 물을 때 호출.
     * 미입력 판정은 하루씩 거슬러 오르며 최대 3일을 확인하므로, 이 자리에서 getDaySpending()을 부르면
     * 읽지도 않는 합계 쿼리가 홈 조회 한 번에 그 일수만큼 덧붙는다.
     */
    public boolean hasDayRecord(Long userId, LocalDate date) {
        return expenseRepository.existsByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE)
                || noSpendDayRepository.existsByUser_IdAndRecordDate(userId, date);
    }

    /**
     * Challenge 도메인이 일별 예산 초과 여부를 판단할 때 호출
     * 특정 유저의 특정 날짜 ACTIVE 지출 합계(원)와 그 날짜에 기록 자체가 있었는지를 반환
     */
    public DaySpending getDaySpending(Long userId, LocalDate date) {
        int totalAmount = expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        return new DaySpending(totalAmount, hasDayRecord(userId, date));
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
     * 최종 종료된 챌린지 기간 잠금(#50) — 유저가 결과 팝업에서 [챌린지 종료]를 누른 뒤에는 그 기간의
     * 기록을 더 못 바꾼다. 수정·삭제뿐 아니라 생성·무지출 기록도 모두 그 기간의 기록을 바꾼다.
     */
    private void validateExpenseChangeAllowed(Long userId, LocalDate date) {
        if (expenseDateLockQuery.isExpenseChangeProhibited(userId, date)) {
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
     * imageKey가 오면 presign만 거친 값이라 ExpenseImageService.validateOwnedAndUploaded()로 소유권(#4)과
     * S3 HeadObject 실제 업로드 여부까지 거쳐야 신뢰할 수 있다
     * (PATCH /expenses/{expenseId}/photos와 동일한 검증 지점 재사용).
     * imageUrl은 더 이상 여기서 만들지 않는다 — getDetail() 조회 시점에 presignGetUrl()로 새로 발급(#6).
     */
    private void createDetailIfPresent(Long userId, Expense expense, String memo, String imageKey) {
        boolean hasMemo = memo != null && !memo.isBlank();
        if (!hasMemo && imageKey == null) {
            return;
        }
        ExpenseDetail detail = ExpenseDetail.of(expense, hasMemo ? memo : null);
        if (imageKey != null) {
            expenseImageService.validateOwnedAndUploaded(userId, imageKey);
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
                                // 없음을 본 두 요청이 동시에 각자 insert하면 PK(expense_id) 충돌이 날 수 있어
                                // 동시성 안전 get-or-create(ExpenseDetailAccess)에 위임한다.
                                expenseDetailAccess.getOrCreate(expense).updateMemo(memo);
                            }
                        }
                );
    }
}
