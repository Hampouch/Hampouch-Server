package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.expense.dto.ExpenseCreateRequest;
import Hampouch.server.domain.expense.dto.ExpenseCreateResponse;
import Hampouch.server.domain.expense.dto.ExpenseDayListResponse;
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
 * 도메인은 달라도 "조회 → 소유권 검증 → 조작" 로직은 동일
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
     * 챌린지가 아예 없으면 검증 자체를 건너뛰고 통과시킨다 — "메인 챌린지가 없는 경우에도 지출 자체는 생성/수정이
     * 가능해야 한다"는 확인 답변 반영. 0714 전체 회의록에도 "온보딩에서 본챌린지 설정을 안 하면 홈화면에
     * '진행중인 챌린지가 없어요' 화면이 뜬다"는 내용이 있어, 챌린지 없이도 앱을 쓰는 상태가
     * 실제로 존재함을 재확인함 — 이 상태의 유저가 지출 자체를 못 쓰게 막을 이유는 없다는 결론과 일치.
     * 챌린지가 있을 때만 그 기간(startDate~endDate) 밖 날짜를 막는다.
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
     * category/emotion이 ETC일 때만 find-or-create로 커스텀 태그를 연결하고, 그 외엔 명시적으로 null 해제한다.
     * expense.getUser()로 이미 갖고 있는 연관관계를 재사용 — LAZY 프록시라도 FK 값(id)은 이미 알고 있어 추가 조회 없이
     * CustomCategory.of()/CustomEmotion.of()에 그대로 넘길 수 있다(userRepository를 여기서 다시 호출할 필요 없음).
     * 내장 enum 라벨과의 중복 검사(EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED 등)는 CustomCategory/CustomEmotion의
     * (user_id, name) 유니크 제약(find-or-create 동시성 가드)과는 별개 책임 — ExpenseErrorCode Javadoc 참조.
     * 동시 요청(같은 유저가 같은 새 커스텀명으로 거의 동시에 두 번 요청, 예: 더블탭)이 그 유니크 제약을 실제로
     * 위반하면 save()가 DataIntegrityViolationException을 던진다 — ID 생성 전략이 IDENTITY라 save() 시점에
     * 즉시 INSERT가 나가 이 예외도 그 자리에서 바로 잡힌다. 재조회 후 기존 행을 반환하는 대신 409로 응답하는
     * 이유: 진 트랜잭션의 영속성 컨텍스트를 그대로 재사용한 재조회는 신뢰할 수 없어 REQUIRES_NEW 트랜잭션 분리가
     * 필요한데, 이 경합 자체가 드문 엣지 케이스라 그 정도 복잡도를 들일 가치가 없다고 판단(1hyok 리뷰 반영,
     * 409면 클라이언트가 그대로 재시도했을 때 다음 조회에서 정상적으로 기존 행을 찾는다).
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
