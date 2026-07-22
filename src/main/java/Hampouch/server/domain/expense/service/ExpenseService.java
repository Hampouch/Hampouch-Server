package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
import Hampouch.server.domain.challenge.dto.DaySpending;
import Hampouch.server.domain.expense.dto.ExpenseDetailResponse;
import Hampouch.server.domain.expense.entity.*;
import Hampouch.server.domain.expense.repository.CustomCategoryRepository;
import Hampouch.server.domain.expense.repository.CustomEmotionRepository;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ExpenseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 지출 5개 우선순위 API(POST/GET/PUT/DELETE /expenses, GET /expenses/day)의 서비스 계층.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 읽기 전용 — 쓰기 메서드만 개별적으로 @Transactional로 덮어씀(ChallengeService와 동일 컨벤션)
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final CustomCategoryRepository customCategoryRepository;
    private final CustomEmotionRepository customEmotionRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;

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
     * PUT /expenses/{expenseId}. ExpenseCreateRequest/Response를 그대로 재사용(두 DTO의 자체 Javadoc 참조).
     * attachCustomTags를 매번 다시 호출하는 이유는 Expense.update() Javadoc과 동일 — category/emotion이 ETC에서
     * 다른 값으로(또는 그 반대로) 바뀌었을 수 있어 customCategory/customEmotion을 매번 새 상태 기준으로 재확정해야 함.
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
     * 챌린지가 아예 없으면 검증 자체를 건너뛰고 통과시킨다.
     * 챌린지가 있을 때만 그 기간(startDate~endDate) 밖 날짜를 막는다.
     * ChallengeService가 이 class의 getDaySpending를 사용하므로, 순환 의존성 방지를 위해 ChallengeRepository를 참조
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
