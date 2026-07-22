package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.Challenge;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 서비스 상태 전이·검증. 리포지토리는 Mockito 목 — DB 불필요(ChallengeServiceTest와 동일 스타일).
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;

    @Mock
    ExpenseRepository expenseRepository;
    @Mock
    CustomCategoryRepository customCategoryRepository;
    @Mock
    CustomEmotionRepository customEmotionRepository;
    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    UserRepository userRepository;

    private ExpenseService service() {
        return new ExpenseService(expenseRepository, customCategoryRepository, customEmotionRepository,
                challengeRepository, userRepository);
    }

    // ---------- create ----------

    @Test
    @DisplayName("category/emotion이 ETC가 아니면 customCategory/customEmotion 조회 없이 그대로 저장된다")
    void create_savesWithoutCustomTagsWhenNotEtc() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));
        ExpenseCreateResponse res = service().create(OWNER, req);

        assertThat(captor.getValue().getName()).isEqualTo("스타벅스");
        assertThat(captor.getValue().getCustomCategory()).isNull();
        assertThat(captor.getValue().getCustomEmotion()).isNull();
        assertThat(res).isNotNull();
        verifyNoInteractions(customCategoryRepository, customEmotionRepository);
    }

    @Test
    @DisplayName("진행 중 챌린지 기간 밖 날짜로 생성하면 400(EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD)을 던진다")
    void create_rejectsDateOutsideChallengePeriod() {
        Challenge ch = Challenge.builder()
                .userId(OWNER)
                .durationDays(14)
                .startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(280000)
                .dailyLimit(20000)
                .resetByPayday(false)
                .paydayDay(null)
                .build();
        when(challengeRepository.findByUserIdAndStatus(OWNER, ChallengeStatus.IN_PROGRESS)).thenReturn(Optional.of(ch));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 20)); // 06-01~06-14 밖

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
    }

    @Test
    @DisplayName("진행 중인 챌린지가 아예 없으면 날짜 범위 검증 없이 자유롭게 생성된다 (요구사항 빈틈 해소)")
    void create_allowsAnyDateWhenNoActiveChallenge() {
        when(challengeRepository.findByUserIdAndStatus(OWNER, ChallengeStatus.IN_PROGRESS)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2020, 1, 1)); // 챌린지가 있었다면 당연히 밖일 날짜

        assertThat(service().create(OWNER, req)).isNotNull();
    }

    @Test
    @DisplayName("category=ETC이고 같은 이름의 커스텀 카테고리가 이미 있으면 새로 만들지 않고 그 행을 재사용한다 (find-or-create)")
    void create_reusesExistingCustomCategory() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        CustomCategory existing = CustomCategory.of(user(OWNER), "스터디카페");
        when(customCategoryRepository.findByUser_IdAndName(OWNER, "스터디카페")).thenReturn(Optional.of(existing));

        var req = new ExpenseCreateRequest("스터디카페 이용권", 8000, ExpenseCategory.ETC, "스터디카페",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));
        service().create(OWNER, req);

        verify(customCategoryRepository, never()).save(any());
        // save()가 안 불렸다는 것만으론 실제 연결까지는 증명 안 됨 — 저장된 Expense에 기존 행이 그대로 붙었는지까지 확인
        assertThat(captor.getValue().getCustomCategory()).isSameAs(existing);
    }

    @Test
    @DisplayName("category=ETC이고 같은 이름의 커스텀 카테고리가 없으면 새로 만들어 저장한다 (find-or-create)")
    void create_createsNewCustomCategoryWhenAbsent() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customCategoryRepository.findByUser_IdAndName(OWNER, "스터디카페")).thenReturn(Optional.empty());
        when(customCategoryRepository.save(any(CustomCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스터디카페 이용권", 8000, ExpenseCategory.ETC, "스터디카페",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));
        service().create(OWNER, req);

        verify(customCategoryRepository).save(any(CustomCategory.class));
    }

    @Test
    @DisplayName("find-or-create 중 동시 요청으로 유니크 제약을 위반하면 500 대신 409(EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED)로 응답한다")
    void create_maps409WhenCustomCategoryRaceViolatesUniqueConstraint() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(customCategoryRepository.findByUser_IdAndName(OWNER, "스터디카페")).thenReturn(Optional.empty());
        when(customCategoryRepository.save(any(CustomCategory.class))).thenThrow(new DataIntegrityViolationException("uq_custom_category_user_name"));

        var req = new ExpenseCreateRequest("스터디카페 이용권", 8000, ExpenseCategory.ETC, "스터디카페",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("커스텀 카테고리 이름이 내장 카테고리 라벨과 겹치면 409(EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED)를 던지고, find-or-create는 시도조차 안 한다")
    void create_rejectsCustomCategoryDuplicatingBuiltinLabel() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));

        var req = new ExpenseCreateRequest("아이스아메리카노", 4500, ExpenseCategory.ETC, "카페", // ExpenseCategory.CAFE 라벨과 동일
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
        verifyNoInteractions(customCategoryRepository);
    }

    @Test
    @DisplayName("emotion=ETC이고 같은 이름의 커스텀 감정이 이미 있으면 새로 만들지 않고 그 행을 재사용한다 (find-or-create) — customCategory와 대칭 케이스")
    void create_reusesExistingCustomEmotion() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        CustomEmotion existing = CustomEmotion.of(user(OWNER), "억울해서");
        when(customEmotionRepository.findByUser_IdAndName(OWNER, "억울해서")).thenReturn(Optional.of(existing));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "억울해서", LocalDate.of(2026, 6, 5));
        service().create(OWNER, req);

        verify(customEmotionRepository, never()).save(any());
        // customCategory와 동일한 이유로 강화 — save() 미호출만으론 부족, 실제 연결까지 확인
        assertThat(captor.getValue().getCustomEmotion()).isSameAs(existing);
    }

    @Test
    @DisplayName("emotion=ETC이고 같은 이름의 커스텀 감정이 없으면 새로 만들어 저장한다 (find-or-create)")
    void create_createsNewCustomEmotionWhenAbsent() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customEmotionRepository.findByUser_IdAndName(OWNER, "억울해서")).thenReturn(Optional.empty());
        when(customEmotionRepository.save(any(CustomEmotion.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "억울해서", LocalDate.of(2026, 6, 5));
        service().create(OWNER, req);

        verify(customEmotionRepository).save(any(CustomEmotion.class));
    }

    @Test
    @DisplayName("find-or-create 중 동시 요청으로 유니크 제약을 위반하면 500 대신 409(EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED)로 응답한다 — customCategory와 대칭 케이스")
    void create_maps409WhenCustomEmotionRaceViolatesUniqueConstraint() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(customEmotionRepository.findByUser_IdAndName(OWNER, "억울해서")).thenReturn(Optional.empty());
        when(customEmotionRepository.save(any(CustomEmotion.class))).thenThrow(new DataIntegrityViolationException("uq_custom_emotion_user_name"));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "억울해서", LocalDate.of(2026, 6, 5));

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("커스텀 감정 이름이 내장 감정 라벨과 겹치면 409(EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED)를 던지고, find-or-create는 시도조차 안 한다")
    void create_rejectsCustomEmotionDuplicatingBuiltinLabel() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "스트레스", LocalDate.of(2026, 6, 5)); // ExpenseEmotion.STRESS 라벨과 동일

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
        verifyNoInteractions(customEmotionRepository);
    }

    // ---------- getDetail ----------

    @Test
    @DisplayName("삭제됐거나 존재하지 않는 expenseId로 조회하면 둘 다 404(EXPENSE_NOT_FOUND)로 합쳐진다 (의도된 설계)")
    void getDetail_notFoundWhenDeletedOrAbsent() {
        when(expenseRepository.findByIdAndStatus(99L, ExpenseStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getDetail(OWNER, 99L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 지출을 조회하면 403(EXPENSE_FORBIDDEN)을 던진다")
    void getDetail_forbiddenWhenNotOwner() {
        Expense expense = expenseOf(OTHER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> service().getDetail(OWNER, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_FORBIDDEN);
    }

    @Test
    @DisplayName("본인 지출을 조회하면 엔티티 필드 그대로 상세 응답에 매핑된다")
    void getDetail_returnsMappedResponse() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        ExpenseDetailResponse res = service().getDetail(OWNER, 1L);

        assertThat(res.name()).isEqualTo("스타벅스");
        assertThat(res.category()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(res.customCategory()).isNull();
    }

    // ---------- update ----------

    @Test
    @DisplayName("ETC에서 다른 카테고리로 바꾸면 남아있던 customCategory 연결이 해제된다")
    void update_clearsCustomCategoryWhenLeavingEtc() {
        CustomCategory tag = CustomCategory.of(user(OWNER), "스터디카페");
        Expense expense = expenseOf(OWNER, ExpenseCategory.ETC, tag, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));
        service().update(OWNER, 1L, req);

        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(expense.getCustomCategory()).isNull();
    }

    @Test
    @DisplayName("ETC에서 다른 감정으로 바꾸면 남아있던 customEmotion 연결이 해제된다 — customCategory와 대칭 케이스")
    void update_clearsCustomEmotionWhenLeavingEtc() {
        CustomEmotion tag = CustomEmotion.of(user(OWNER), "억울해서");
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.ETC, tag);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));
        service().update(OWNER, 1L, req);

        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.STRESS);
        assertThat(expense.getCustomEmotion()).isNull();
    }

    @Test
    @DisplayName("진행 중 챌린지 기간 밖 날짜로 수정하면 400(EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD)을 던진다")
    void update_rejectsDateOutsideChallengePeriod() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        Challenge ch = Challenge.builder()
                .userId(OWNER)
                .durationDays(14)
                .startDate(LocalDate.of(2026, 6, 1))
                .budgetTotal(280000)
                .dailyLimit(20000)
                .resetByPayday(false)
                .paydayDay(null)
                .build();
        when(challengeRepository.findByUserIdAndStatus(OWNER, ChallengeStatus.IN_PROGRESS)).thenReturn(Optional.of(ch));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 20)); // 06-01~06-14 밖

        assertThatThrownBy(() -> service().update(OWNER, 1L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
    }

    @Test
    @DisplayName("남의 지출을 수정하려 하면 403(EXPENSE_FORBIDDEN)을 던지고 필드는 그대로다")
    void update_forbiddenWhenNotOwner() {
        Expense expense = expenseOf(OTHER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        var req = new ExpenseCreateRequest("변조 시도", 1, ExpenseCategory.ETC, "해킹",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5));

        assertThatThrownBy(() -> service().update(OWNER, 1L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_FORBIDDEN);
        assertThat(expense.getName()).isEqualTo("스타벅스"); // 원본 유지
    }

    // ---------- delete ----------

    @Test
    @DisplayName("삭제하면 물리 삭제가 아니라 status만 DELETED로 바뀐다")
    void delete_softDeletes() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        service().delete(OWNER, 1L);

        assertThat(expense.getStatus()).isEqualTo(ExpenseStatus.DELETED);
        verify(expenseRepository, never()).delete(any());
        verify(expenseRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("이미 삭제됐거나 없는 expenseId를 삭제하려 하면 404(EXPENSE_NOT_FOUND)를 던진다")
    void delete_notFoundWhenAlreadyDeleted() {
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(OWNER, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_NOT_FOUND);
    }

    // ---------- getDayList ----------

    @Test
    @DisplayName("하루 목록 조회는 리포지토리 결과를 합계와 함께 그대로 응답에 담는다")
    void getDayList_buildsResponseFromRepository() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        Expense e1 = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        Expense e2 = expenseOf(OWNER, ExpenseCategory.DELIVERY, null, ExpenseEmotion.COMPENSATION, null);
        when(expenseRepository.findByUser_IdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE))
                .thenReturn(List.of(e1, e2));

        ExpenseDayListResponse res = service().getDayList(OWNER, date);

        assertThat(res.expenses()).hasSize(2);
        assertThat(res.totalAmount()).isEqualTo(e1.getPrice() + e2.getPrice());
    }

    // ---------- fixtures ----------

    private static User user(Long id) {
        User user = User.createLocalUser("user" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** 이름/금액/날짜는 테스트마다 안 중요해서 고정값으로 통일 — 필요한 케이스만 category/customCategory/emotion/customEmotion을 바꿔 받는다. */
    private static Expense expenseOf(Long ownerId, ExpenseCategory category, CustomCategory customCategory,
                                      ExpenseEmotion emotion, CustomEmotion customEmotion) {
        Expense expense = Expense.of("스타벅스", 5000, category, emotion, LocalDate.of(2026, 6, 5), user(ownerId));
        expense.assignCustomCategory(customCategory);
        expense.assignCustomEmotion(customEmotion);
        return expense;
    }
}
