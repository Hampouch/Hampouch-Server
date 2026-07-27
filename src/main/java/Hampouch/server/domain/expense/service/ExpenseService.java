package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseCustomTagsResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.dto.ExpenseSummaryResponse;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.CustomCategoryRepository;
import Hampouch.server.domain.expense.repository.CustomEmotionRepository;
import Hampouch.server.domain.expense.repository.ExpenseDailyTotal;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 읽기 전용 — 쓰기 메서드만 개별적으로 @Transactional로 덮어씀
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final CustomCategoryRepository customCategoryRepository;
    private final CustomEmotionRepository customEmotionRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final Clock clock; //validateWithinChallengePeriod()가 챌린지가 없을 때 오늘 / 어제 날짜를 확인하기 위한 기준
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
        Expense expense = Expense.of(request.name(), request.price(), request.category(), request.emotion(), request.date(), user);
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        return ExpenseCreateResponse.from(expenseRepository.save(expense));
    }

    /** GET /expenses/{expenseId}. */
    public ExpenseDetailResponse getDetail(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        return ExpenseDetailResponse.from(expense);
    }

    /**
     * PUT /expenses/{expenseId}. ExpenseCreateRequest/Response를 그대로 재사용
     * attachCustomTags를 매번 다시 호출하는 이유: category/emotion이 ETC에서 다른 값으로(또는 그 반대로)
     * 바뀌었을 수 있어 customCategory/customEmotion을 매번 새 상태 기준으로 재확정해야 함.
     */
    @Transactional
    public ExpenseCreateResponse update(Long userId, Long expenseId, ExpenseCreateRequest request) {
        Expense expense = loadOwned(userId, expenseId);
        validateWithinChallengePeriod(userId, request.date());

        expense.update(request.name(), request.price(), request.category(), request.emotion(), request.date());
        attachCustomTags(expense, request.category(), request.customCategory(), request.emotion(), request.customEmotion());

        return ExpenseCreateResponse.from(expense);
    }

    /** DELETE /expenses/{expenseId} — 소프트 삭제(Expense.delete()), 물리 삭제 아님. */
    @Transactional
    public void delete(Long userId, Long expenseId) {
        Expense expense = loadOwned(userId, expenseId);
        expense.delete();
    }

    /** GET /expenses/day — 하루 목록 + 합계. 삭제된 지출은 findByUser_IdAndExpenseDateAndStatus에서 이미 제외됨. */
    public ExpenseDayListResponse getDayList(Long userId, LocalDate date) {
        List<Expense> expenses = expenseRepository.findByUser_IdAndExpenseDateAndStatus(userId, date, ExpenseStatus.ACTIVE);
        return ExpenseDayListResponse.from(date, expenses);
    }

    /** GET /expenses/custom-tags — 유저가 등록한 커스텀 카테고리/감정 태그 목록(이슈 #37). 없어도 빈 배열로 정상 응답. */
    public ExpenseCustomTagsResponse getCustomTags(Long userId) {
        List<CustomEmotion> emotions = customEmotionRepository.findAllByUser_IdOrderByCreatedAtAsc(userId);
        List<CustomCategory> categories = customCategoryRepository.findAllByUser_IdOrderByCreatedAtAsc(userId);
        return ExpenseCustomTagsResponse.of(emotions, categories);
    }

    /** GET /expenses/summary/week — stDate가 속한 주(일~토)의 합계·일별 내역 */
    public ExpenseSummaryResponse getWeekSummary(Long userId, LocalDate stDate) {
        int daysSinceSunday = stDate.getDayOfWeek().getValue() % 7; // MONDAY=1..SATURDAY=6, SUNDAY=7→0
        LocalDate periodStart = stDate.minusDays(daysSinceSunday);
        LocalDate periodEnd = periodStart.plusDays(6);
        return buildSummary(userId, periodStart, periodEnd);
    }

    /** GET /expenses/summary/month — stMonth 해당 월의 합계·일별 내역 */
    public ExpenseSummaryResponse getMonthSummary(Long userId, YearMonth stMonth) {
        LocalDate periodStart = stMonth.atDay(1);
        LocalDate periodEnd = stMonth.atEndOfMonth();
        return buildSummary(userId, periodStart, periodEnd);
    }

    /** week/month 공용 조립 — 날짜별 합계 조회 후 DTO에 위임(오늘 날짜는 dailyAverage의 경과일수 계산에 필요). */
    private ExpenseSummaryResponse buildSummary(Long userId, LocalDate periodStart, LocalDate periodEnd) {
        List<ExpenseDailyTotal> dailyTotals = expenseRepository.sumGroupedByDate(
                userId, ExpenseStatus.ACTIVE, periodStart, periodEnd);
        return ExpenseSummaryResponse.of(periodStart, periodEnd, dailyTotals, LocalDate.now(clock));
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
     * 챌린지가 아예 없으면 오늘/어제 날짜만 허용한다
     * 제한을 아예 두지 않으면 챌린지 휴식기에 모든 날짜의 지출을 입력할 수 있게 되어
     * 데이터 비일관성이 발생할 수 있다
     */
    private void validateWithinChallengePeriod(Long userId, LocalDate date) {
        challengeRepository.findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS).ifPresentOrElse(
                challenge -> {
                    if (date.isBefore(challenge.getStartDate()) || date.isAfter(challenge.getEndDate())) {
                        throw new CustomException(ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
                    }
                },
                () -> {
                    LocalDate today = LocalDate.now(clock);
                    if (!date.equals(today) && !date.equals(today.minusDays(1))) {
                        throw new CustomException(ExpenseErrorCode.EXPENSE_DATE_OUT_OF_RECENT_RANGE);
                    }
                }
        );
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
