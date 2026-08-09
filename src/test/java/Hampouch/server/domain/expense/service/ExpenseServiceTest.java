package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 서비스 상태 전이·검증. 리포지토리는 Mockito 목 — DB 불필요
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 5);
    private static final LocalDateTime ACCOUNT_CREATED_AT = LocalDateTime.of(2025, 1, 1, 0, 0); // user(id) 픽스처의 고정 가입일

    @Mock
    ExpenseRepository expenseRepository;
    @Mock
    NoSpendDayRepository noSpendDayRepository;
    @Mock
    ChallengeRepository challengeRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ExpenseDetailRepository expenseDetailRepository;
    @Mock
    ExpenseImageService expenseImageService;

    private ExpenseService service() {
        return serviceAt(LocalDate.of(2026, 6, 6));
    }

    /** 오늘을 직접 고정해야 하는 케이스(주간/월간 요약의 dailyAverage 계산) */
    private ExpenseService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new ExpenseService(expenseRepository, expenseDetailRepository,noSpendDayRepository, challengeRepository, userRepository, expenseImageService, clock);
    }

    // ---------- create ----------

    @Test
    @DisplayName("category/emotion이 ETC가 아니면 customCategory/customEmotion 없이 그대로 저장된다")
    void create_savesWithoutCustomTagsWhenNotEtc() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        ExpenseCreateResponse res = service().create(OWNER, req);

        assertThat(captor.getValue().getName()).isEqualTo("스타벅스");
        assertThat(captor.getValue().getCustomCategory()).isNull();
        assertThat(captor.getValue().getCustomEmotion()).isNull();
        assertThat(res).isNotNull();
    }

    @Test
    @DisplayName("0원 지출도 카테고리·감정을 가진 일반 지출로 저장하고 같은 날짜의 '오늘은 안 썼어요' 기록을 지운다")
    void create_savesZeroPriceExpenseAsRegularExpense() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("무료 음료", 0, ExpenseCategory.CAFE, null,
                ExpenseEmotion.CONVENIENCE, null, TODAY, null, null);

        service().create(OWNER, req);

        Expense saved = captor.getValue();
        assertThat(saved.getPrice()).isZero();
        assertThat(saved.getCategory()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(saved.getEmotion()).isEqualTo(ExpenseEmotion.CONVENIENCE);
        verify(noSpendDayRepository).deleteByUser_IdAndRecordDate(OWNER, TODAY);
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
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 20), null, null); // 06-01~06-14 밖

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
    }

    @Test
    @DisplayName("진행 중인 챌린지가 없으면 날짜 범위 검증 없이 자유롭게 생성된다")
    void create_allowsAnyDateWhenNoActiveChallenge() {
        when(challengeRepository.findByUserIdAndStatus(OWNER, ChallengeStatus.IN_PROGRESS)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2020, 1, 1), null, null); // 챌린지가 있었다면 당연히 밖일 날짜, 없으면 막히지 않아야 함

        assertThat(service().create(OWNER, req)).isNotNull();
    }

    @Test
    @DisplayName("category=ETC면 customCategory 문자열이 Expense에 그대로 기록된다")
    void create_storesCustomCategoryStringWhenEtc() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스터디카페 이용권", 8000, ExpenseCategory.ETC, "스터디카페",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().create(OWNER, req);

        assertThat(captor.getValue().getCustomCategory()).isEqualTo("스터디카페");
    }

    @Test
    @DisplayName("커스텀 카테고리 이름이 내장 카테고리 라벨과 겹치면 409(EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED)를 던진다")
    void create_rejectsCustomCategoryDuplicatingBuiltinLabel() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));

        var req = new ExpenseCreateRequest("아이스아메리카노", 4500, ExpenseCategory.ETC, "카페", // ExpenseCategory.CAFE 라벨과 동일
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_CATEGORY_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("emotion=ETC면 customEmotion 문자열이 Expense에 그대로 기록된다 — customCategory와 대칭 케이스")
    void create_storesCustomEmotionStringWhenEtc() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "억울해서", LocalDate.of(2026, 6, 5), null, null);

        service().create(OWNER, req);

        assertThat(captor.getValue().getCustomEmotion()).isEqualTo("억울해서");
    }

    @Test
    @DisplayName("커스텀 감정 이름이 내장 감정 라벨과 겹치면 409(EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED)를 던진다")
    void create_rejectsCustomEmotionDuplicatingBuiltinLabel() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.ETC, "스트레스", LocalDate.of(2026, 6, 5), null, null); // ExpenseEmotion.STRESS 라벨과 동일

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_CUSTOM_EMOTION_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("이름/카테고리/이유를 전부 건너뛰면(null) category/emotion은 ETC로 흡수되고 name/customCategory/customEmotion은 null로 저장된다")
    void create_absorbsSkippedFieldsIntoEtcAndNull() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest(null, 5000, null, null, null, null, LocalDate.of(2026, 6, 5), null, null);
        service().create(OWNER, req);

        Expense saved = captor.getValue();
        assertThat(saved.getName()).isNull();
        assertThat(saved.getCategory()).isEqualTo(ExpenseCategory.ETC);
        assertThat(saved.getEmotion()).isEqualTo(ExpenseEmotion.ETC);
        assertThat(saved.getCustomCategory()).isNull();
        assertThat(saved.getCustomEmotion()).isNull();
    }

    /**
     * name=null(건너뛰기)과 별개로, 클라이언트가 name 필드 자체는 보내되 빈 문자열("")을 보내는 경우를 방어.
     * @Size(max=90)는 빈 문자열을 통과시키므로 DTO 검증만으로는 안 걸러지고, 서비스에서 blank를 null로 정규화해야
     * "제목 없음" 표시 규칙(name=null)이 일관되게 유지된다 — 그렇지 않으면 빈 문자열이 그대로 저장돼 화면에서
     * null과 다르게 처리될 여지가 생긴다.
     */
    @Test
    @DisplayName("name이 빈 문자열(\"\")이면 null로 정규화되어 저장된다 — 지출명 입력칸을 비워둔 채 제출한 경우 방어")
    void create_normalizesBlankNameToNull() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        when(expenseRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().create(OWNER, req);

        assertThat(captor.getValue().getName()).isNull();
    }

    // ---------- recordNoSpend ----------

    @Test
    @DisplayName("ACTIVE 지출이 없고 아직 '오늘은 안 썼어요'를 기록하지 않은 날짜에 '오늘은 안 썼어요'를 누르면 기록을 저장한다")
    void recordNoSpend_savesNoSpendDayWhenNothingRecorded() {
        User user = user(OWNER);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(false);
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, TODAY)).thenReturn(false);
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(user));
        ArgumentCaptor<NoSpendDay> captor = ArgumentCaptor.forClass(NoSpendDay.class);

        service().recordNoSpend(OWNER, new NoSpendRecordRequest(TODAY));

        verify(noSpendDayRepository).save(captor.capture());
        NoSpendDay saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getRecordDate()).isEqualTo(TODAY);
        verify(expenseRepository, never()).save(any());
        assertThat(user.getLastUpdated()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("그 날짜에 ACTIVE 지출이 이미 있으면 '오늘은 안 썼어요' 기록을 저장하지 않는다")
    void recordNoSpend_isIdempotentWhenAnyExpenseExists() {
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(true);

        service().recordNoSpend(OWNER, new NoSpendRecordRequest(TODAY));

        verify(noSpendDayRepository, never()).save(any());
        verifyNoInteractions(noSpendDayRepository);
        verifyNoInteractions(challengeRepository, userRepository);
    }

    @Test
    @DisplayName("그 날짜에 '오늘은 안 썼어요' 기록이 이미 있으면 중복 저장하지 않는다")
    void recordNoSpend_isIdempotentWhenNoSpendDayExists() {
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(false);
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, TODAY)).thenReturn(true);

        service().recordNoSpend(OWNER, new NoSpendRecordRequest(TODAY));

        verify(noSpendDayRepository, never()).save(any());
        verifyNoInteractions(challengeRepository, userRepository);
    }

    @Test
    @DisplayName("챌린지 기간 검증 없이 빈 날짜에 '오늘은 안 썼어요' 기록을 저장한다")
    void recordNoSpend_savesEmptyDateOutsideChallengePeriod() {
        LocalDate outside = LocalDate.of(2026, 6, 20);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, outside, ExpenseStatus.ACTIVE))
                .thenReturn(false);
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, outside)).thenReturn(false);
        User user = user(OWNER);
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(user));

        service().recordNoSpend(OWNER, new NoSpendRecordRequest(outside));

        ArgumentCaptor<NoSpendDay> captor = ArgumentCaptor.forClass(NoSpendDay.class);
        verify(noSpendDayRepository).save(captor.capture());
        assertThat(captor.getValue().getRecordDate()).isEqualTo(outside);
        assertThat(user.getLastUpdated()).isEqualTo(outside);
        verifyNoInteractions(challengeRepository);
    }

    // ---------- create: memo/이미지 ----------

    @Test
    @DisplayName("memo만 있으면 ExpenseDetail이 memo만 채워진 채로 저장된다")
    void create_savesDetailWithMemoOnly() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ExpenseDetail> captor = ArgumentCaptor.forClass(ExpenseDetail.class);
        when(expenseDetailRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), "오늘 기분 좋아서", null);

        service().create(OWNER, req);

        assertThat(captor.getValue().getMemo()).isEqualTo("오늘 기분 좋아서");
        assertThat(captor.getValue().getImageKey()).isNull();
        verify(expenseImageService, never()).resolveImageUrl(any());
    }

    @Test
    @DisplayName("imageKey가 있으면 resolveImageUrl로 검증한 imageUrl까지 채워 ExpenseDetail을 저장한다")
    void create_savesDetailWithImageKey() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenseImageService.resolveImageUrl("expenses/abc.jpg")).thenReturn("https://bucket.s3.region.amazonaws.com/expenses/abc.jpg");
        ArgumentCaptor<ExpenseDetail> captor = ArgumentCaptor.forClass(ExpenseDetail.class);
        when(expenseDetailRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, "expenses/abc.jpg");

        service().create(OWNER, req);

        assertThat(captor.getValue().getImageKey()).isEqualTo("expenses/abc.jpg");
        assertThat(captor.getValue().getImageUrl()).isEqualTo("https://bucket.s3.region.amazonaws.com/expenses/abc.jpg");
        assertThat(captor.getValue().getMemo()).isNull();
    }

    @Test
    @DisplayName("memo/imageKey가 둘 다 없으면 ExpenseDetail을 아예 저장하지 않는다 — 진짜 optional 1:1 원칙")
    void create_skipsDetailWhenNeitherMemoNorImageKeyPresent() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().create(OWNER, req);

        verify(expenseDetailRepository, never()).save(any());
    }

    @Test
    @DisplayName("imageKey 검증에 실패하면(HeadObject 미확인) create() 전체가 실패하며 예외가 그대로 전파된다")
    void create_propagatesExceptionWhenImageKeyNotUploaded() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenseImageService.resolveImageUrl("expenses/missing.jpg"))
                .thenThrow(new CustomException(ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, "expenses/missing.jpg");

        assertThatThrownBy(() -> service().create(OWNER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_IMAGE_NOT_UPLOADED);
        verify(expenseDetailRepository, never()).save(any());
    }


    // ---------- lastUpdated ----------

    @Test
    @DisplayName("지출 날짜가 기존 lastUpdated보다 최근이면 그 지출 날짜로 전진한다")
    void create_advancesUserLastUpdatedWhenExpenseDateIsMoreRecent() {
        User user = user(OWNER);
        user.updateLastUpdated(LocalDate.of(2026, 5, 1)); // 기존엔 한 달 전이 마지막 커버리지였다고 가정
        when(userRepository.getReferenceById(OWNER)).thenReturn(user);
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, TODAY, null, null);

        service().create(OWNER, req);

        assertThat(user.getLastUpdated()).isEqualTo(TODAY); // 더 최근인 지출 날짜로 전진
    }

    @Test
    @DisplayName("지난 지출을 소급 입력해도(지출 날짜가 기존 lastUpdated보다 과거) lastUpdated는 뒤로 물러나지 않는다")
    void create_doesNotRevertUserLastUpdatedWhenExpenseDateIsOlder() {
        User user = user(OWNER);
        user.updateLastUpdated(TODAY); // 이미 오늘 지출로 커버리지가 최신인 상태
        when(userRepository.getReferenceById(OWNER)).thenReturn(user);
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("영수증 소급 입력", 3000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2020, 1, 1), null, null); // 오래된 영수증을 뒤늦게 등록

        service().create(OWNER, req);

        assertThat(user.getLastUpdated()).isEqualTo(TODAY); // 2020-01-01로 후퇴하지 않고 기존 최신값 유지
    }

    @Test
    @DisplayName("삭제하면 User.lastUpdated는 남은 ACTIVE 지출 중 가장 최근 expenseDate(그 지출이 커버하는 날짜)로 되돌아간다 ")
    void delete_revertsLastUpdatedToMostRecentRemainingActiveExpense() {
        User user = user(OWNER);
        user.updateLastUpdated(TODAY); // 방금 지운 지출의 날짜에 맞춰 갱신됐던 상태를 흉내
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, TODAY, user);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        LocalDate remainingDate = LocalDate.of(2026, 6, 3);
        Expense remaining = Expense.of("편의점", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, remainingDate, user);
        when(expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(eq(OWNER), eq(ExpenseStatus.ACTIVE), any()))
                .thenReturn(Optional.of(remaining));

        service().delete(OWNER, 1L);

        assertThat(user.getLastUpdated()).isEqualTo(remainingDate); // TODAY가 아니라 남은 지출의 expenseDate로 되돌아감
    }

    @Test
    @DisplayName("삭제 후 남은 ACTIVE 지출이 하나도 없으면 User.lastUpdated는 null로 되돌아간다")
    void delete_revertsLastUpdatedToNullWhenNoActiveExpensesRemain() {
        User user = user(OWNER);
        user.updateLastUpdated(TODAY);
        Expense expense = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, TODAY, user);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(eq(OWNER), eq(ExpenseStatus.ACTIVE), any()))
                .thenReturn(Optional.empty());

        service().delete(OWNER, 1L);

        assertThat(user.getLastUpdated()).isNull();
    }
    @Test
    @DisplayName("최신 지출 삭제 후 남은 기록 중 가장 최근 날짜가 무지출 기록이면 lastUpdated를 그 날짜로 갱신한다")
    void delete_usesLatestNoSpendDayWhenItIsNewerThanRemainingExpense() {
        User user = user(OWNER);
        user.updateLastUpdated(TODAY);
        Expense deleted = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, TODAY, user);
        LocalDate noSpendDate = TODAY.minusDays(1);
        LocalDate remainingExpenseDate = TODAY.minusDays(2);
        Expense remaining = Expense.of("편의점", 3000, ExpenseCategory.CONVENIENCE_STORE,
                ExpenseEmotion.CONVENIENCE, remainingExpenseDate, user);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(deleted));
        when(expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(
                OWNER, ExpenseStatus.ACTIVE, 1L)).thenReturn(Optional.of(remaining));
        when(noSpendDayRepository.findTopByUser_IdOrderByRecordDateDesc(OWNER))
                .thenReturn(Optional.of(NoSpendDay.of(user, noSpendDate)));

        service().delete(OWNER, 1L);

        assertThat(user.getLastUpdated()).isEqualTo(noSpendDate);
    }

    @Test
    @DisplayName("최신 지출 삭제 후 남은 기록 중 가장 최근 날짜가 ACTIVE 지출이면 lastUpdated를 그 지출 날짜로 갱신한다")
    void delete_usesLatestRemainingExpenseWhenItIsNewerThanNoSpendDay() {
        User user = user(OWNER);
        user.updateLastUpdated(TODAY);
        Expense deleted = Expense.of("스타벅스", 5000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, TODAY, user);
        LocalDate remainingExpenseDate = TODAY.minusDays(1);
        Expense remaining = Expense.of("편의점", 3000, ExpenseCategory.CONVENIENCE_STORE,
                ExpenseEmotion.CONVENIENCE, remainingExpenseDate, user);
        LocalDate noSpendDate = TODAY.minusDays(2);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(deleted));
        when(expenseRepository.findTopByUser_IdAndStatusAndIdNotOrderByExpenseDateDesc(
                OWNER, ExpenseStatus.ACTIVE, 1L)).thenReturn(Optional.of(remaining));
        when(noSpendDayRepository.findTopByUser_IdOrderByRecordDateDesc(OWNER))
                .thenReturn(Optional.of(NoSpendDay.of(user, noSpendDate)));

        service().delete(OWNER, 1L);

        assertThat(user.getLastUpdated()).isEqualTo(remainingExpenseDate);
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

    @Test
    @DisplayName("ExpenseDetail이 없으면(memo/이미지 둘 다 없던 지출) memo/imageUrl 모두 null로 응답한다")
    void getDetail_returnsNullMemoAndImageUrlWhenDetailAbsent() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());

        ExpenseDetailResponse res = service().getDetail(OWNER, 1L);

        assertThat(res.memo()).isNull();
        assertThat(res.imageUrl()).isNull();
    }

    @Test
    @DisplayName("ExpenseDetail이 있으면 memo/imageUrl이 그대로 응답에 매핑된다")
    void getDetail_returnsMemoAndImageUrlWhenDetailPresent() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail detail = ExpenseDetail.of(expense, "맛있었다");
        detail.attachImage("expenses/abc.jpg", "https://bucket.s3.region.amazonaws.com/expenses/abc.jpg");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(detail));

        ExpenseDetailResponse res = service().getDetail(OWNER, 1L);

        assertThat(res.memo()).isEqualTo("맛있었다");
        assertThat(res.imageUrl()).isEqualTo("https://bucket.s3.region.amazonaws.com/expenses/abc.jpg");
    }

    // ---------- update ----------

    @Test
    @DisplayName("ETC에서 다른 카테고리로 바꾸면 남아있던 customCategory 값이 해제된다")
    void update_clearsCustomCategoryWhenLeavingEtc() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.ETC, "스터디카페", ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(expense.getCustomCategory()).isNull();
    }

    @Test
    @DisplayName("지출 날짜를 수정하면 대상 날짜의 '오늘은 안 썼어요' 기록을 지운다")
    void update_removesNoSpendDayOnTargetDate() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        LocalDate targetDate = TODAY.minusDays(1);
        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, targetDate, null, null);

        service().update(OWNER, 1L, req);

        verify(noSpendDayRepository).deleteByUser_IdAndRecordDate(OWNER, targetDate);
    }

    @Test
    @DisplayName("ETC에서 다른 감정으로 바꾸면 남아있던 customEmotion 값이 해제된다 — customCategory와 대칭 케이스")
    void update_clearsCustomEmotionWhenLeavingEtc() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.ETC, "억울해서");
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.STRESS);
        assertThat(expense.getCustomEmotion()).isNull();
    }

    @Test
    @DisplayName("수정할 때도 카테고리/이유를 건너뛰면(null) ETC로 흡수되고 기존 customCategory/customEmotion은 해제된다 ")
    void update_absorbsSkippedFieldsIntoEtc() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.ETC, "스터디카페", ExpenseEmotion.ETC, "억울해서");
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest(null, 5000, null, null, null, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        assertThat(expense.getName()).isNull();
        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.ETC);
        assertThat(expense.getEmotion()).isEqualTo(ExpenseEmotion.ETC);
        assertThat(expense.getCustomCategory()).isNull();
        assertThat(expense.getCustomEmotion()).isNull();
    }

    @Test
    @DisplayName("name이 공백 문자만 있으면(\"   \") 수정 시에도 null로 정규화된다 — 기존에 실제 제목이 있었어도 덮어써진다")
    void update_normalizesBlankNameToNull() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null); // 기존 name: "스타벅스"
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("   ", 6000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        assertThat(expense.getName()).isNull();
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
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 20), null, null); // 06-01~06-14 밖

        assertThatThrownBy(() -> service().update(OWNER, 1L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_DATE_OUT_OF_CHALLENGE_PERIOD);
    }

    @Test
    @DisplayName("진행 중인 챌린지가 없으면 오늘/어제 범위 밖 날짜로 수정해도 막지 않는다 — create와 대칭 케이스")
    void update_allowsAnyDateWhenNoActiveChallenge() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(challengeRepository.findByUserIdAndStatus(OWNER, ChallengeStatus.IN_PROGRESS)).thenReturn(Optional.empty());

        LocalDate today = LocalDate.of(2026, 6, 6);
        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2020, 1, 1), null, null);

        assertThat(serviceAt(today).update(OWNER, 1L, req)).isNotNull();
    }

    @Test
    @DisplayName("날짜를 바꾸지 않고 다른 필드만 수정하면 챌린지 기간 검증을 아예 건너뛴다 (날짜가 실제로 바뀔 때만 검증)")
    void update_skipsChallengePeriodValidationWhenDateUnchanged() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null); // 날짜: 2026-06-05
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("스타벅스 아메리카노", 6000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        verify(challengeRepository, never()).findByUserIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("User.lastUpdated가 null인 상태에서 수정하면 isAfter(null) NPE 없이 전체 재계산으로 반영된다 (회귀 방지)")
    void update_setsLastUpdatedViaRecomputeWhenCurrentlyNull() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        User user = expense.getUser(); // user() 픽스처는 lastUpdated가 null인 상태
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        LocalDate recomputed = LocalDate.of(2026, 6, 10);
        when(expenseRepository.findTopByUser_IdAndStatusOrderByExpenseDateDesc(OWNER, ExpenseStatus.ACTIVE))
                .thenReturn(Optional.of(Expense.of("편의점", 3000, ExpenseCategory.CAFE, ExpenseEmotion.STRESS, recomputed, user)));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        service().update(OWNER, 1L, req);

        assertThat(user.getLastUpdated()).isEqualTo(recomputed);
    }

    @Test
    @DisplayName("남의 지출을 수정하려 하면 403(EXPENSE_FORBIDDEN)을 던지고 필드는 그대로다")
    void update_forbiddenWhenNotOwner() {
        Expense expense = expenseOf(OTHER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));

        var req = new ExpenseCreateRequest("변조 시도", 1, ExpenseCategory.ETC, "해킹",
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, null);

        assertThatThrownBy(() -> service().update(OWNER, 1L, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExpenseErrorCode.EXPENSE_FORBIDDEN);
        assertThat(expense.getName()).isEqualTo("스타벅스"); // 원본 유지
    }

    @Test
    @DisplayName("ExpenseDetail이 없던 지출에 memo를 추가하면 새로 생성된다 — update()의 get-or-create")
    void update_createsDetailWhenAddingMemoToExpenseWithoutOne() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());
        ArgumentCaptor<ExpenseDetail> captor = ArgumentCaptor.forClass(ExpenseDetail.class);
        when(expenseDetailRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), "새로 남긴 메모", null);

        service().update(OWNER, 1L, req);

        assertThat(captor.getValue().getMemo()).isEqualTo("새로 남긴 메모");
    }

    @Test
    @DisplayName("이미 ExpenseDetail이 있는 지출에서 memo를 빈 문자열로 보내면 null로 정규화되어 반영된다")
    void update_normalizesBlankMemoToNullOnExistingDetail() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        ExpenseDetail detail = ExpenseDetail.of(expense, "기존 메모");
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.of(detail));

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), "", null);

        service().update(OWNER, 1L, req);

        assertThat(detail.getMemo()).isNull();
    }

    @Test
    @DisplayName("update() 요청에 imageKey를 담아도 무시된다 — 이미지 변경은 presign+PATCH 전용")
    void update_ignoresImageKey() {
        Expense expense = expenseOf(OWNER, ExpenseCategory.CAFE, null, ExpenseEmotion.STRESS, null);
        when(expenseRepository.findByIdAndStatus(1L, ExpenseStatus.ACTIVE)).thenReturn(Optional.of(expense));
        when(expenseDetailRepository.findByExpenseId(1L)).thenReturn(Optional.empty());

        var req = new ExpenseCreateRequest("스타벅스", 5000, ExpenseCategory.CAFE, null,
                ExpenseEmotion.STRESS, null, LocalDate.of(2026, 6, 5), null, "expenses/ignored.jpg");

        service().update(OWNER, 1L, req);

        verify(expenseImageService, never()).resolveImageUrl(any());
        verify(expenseDetailRepository, never()).save(any());
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
        assertThat(res.hasRecord()).isTrue();
    }

    @Test
    @DisplayName("일반 0원 지출도 카테고리·감정을 가진 목록 항목으로 돌려준다")
    void getDayList_returnsZeroPriceExpenseAsRegularItem() {
        Expense zero = Expense.of("무료 음료", 0, ExpenseCategory.CAFE, ExpenseEmotion.CONVENIENCE, TODAY, user(OWNER));
        when(expenseRepository.findByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(List.of(zero));

        ExpenseDayListResponse res = service().getDayList(OWNER, TODAY);

        assertThat(res.totalAmount()).isZero();
        assertThat(res.hasRecord()).isTrue();
        assertThat(res.expenses()).hasSize(1);
        assertThat(res.expenses().getFirst().category()).isEqualTo(ExpenseCategory.CAFE);
        assertThat(res.expenses().getFirst().emotion()).isEqualTo(ExpenseEmotion.CONVENIENCE);
    }

    @Test
    @DisplayName("'오늘은 안 썼어요' 기록만 저장된 날은 빈 목록과 합계 0, hasRecord=true를 돌려준다")
    void getDayList_returnsNoSpendDayWithoutExpenseItem() {
        when(expenseRepository.findByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(List.of());
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, TODAY)).thenReturn(true);

        ExpenseDayListResponse res = service().getDayList(OWNER, TODAY);

        assertThat(res.totalAmount()).isZero();
        assertThat(res.hasRecord()).isTrue();
        assertThat(res.expenses()).isEmpty();
    }

    @Test
    @DisplayName("행이 하나도 없는 날은 합계 0과 hasRecord=false로 돌려준다")
    void getDayList_returnsNoRecordWhenNothingWasEntered() {
        when(expenseRepository.findByUser_IdAndExpenseDateAndStatus(OWNER, TODAY, ExpenseStatus.ACTIVE))
                .thenReturn(List.of());
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, TODAY)).thenReturn(false);

        ExpenseDayListResponse res = service().getDayList(OWNER, TODAY);

        assertThat(res.totalAmount()).isZero();
        assertThat(res.hasRecord()).isFalse();
        assertThat(res.expenses()).isEmpty();
    }

    // ---------- getWeekSummary / getMonthSummary ----------

    @Test
    @DisplayName("standardDate가 일요일이면 그 날짜 자체가 기간 시작일이다")
    void getWeekSummary_sundayStandardDateIsPeriodStartItself() {
        LocalDate periodStart = LocalDate.of(2026, 6, 7); // 일
        LocalDate periodEnd = LocalDate.of(2026, 6, 13); // 토
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE, periodStart, periodEnd))
                .thenReturn(List.of(new ExpenseDailyTotal(LocalDate.of(2026, 6, 7), 5000L)));

        ExpenseSummaryResponse res = serviceAt(LocalDate.of(2026, 6, 20))
                .getWeekSummary(OWNER, LocalDate.of(2026, 6, 7));

        assertThat(res.periodStart()).isEqualTo(periodStart);
        assertThat(res.periodEnd()).isEqualTo(periodEnd);
    }

    @Test
    @DisplayName("주간 조회는 standardDate가 속한 일~토를 기간으로 잡고, 이미 끝난 주면 경과일수를 7일로 평균을 낸다")
    void getWeekSummary_completedPeriodAveragesOverFullWeek() {
        LocalDate periodStart = LocalDate.of(2026, 6, 7); // 일
        LocalDate periodEnd = LocalDate.of(2026, 6, 13); // 토
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE, periodStart, periodEnd))
                .thenReturn(List.of(
                        new ExpenseDailyTotal(LocalDate.of(2026, 6, 8), 10000L),
                        new ExpenseDailyTotal(LocalDate.of(2026, 6, 10), 20000L)));

        // standardDate=수요일(06-10), 오늘 = 06-20 — 조회 대상 주가 이미 완전히 지난 시점
        ExpenseSummaryResponse res = serviceAt(LocalDate.of(2026, 6, 20))
                .getWeekSummary(OWNER, LocalDate.of(2026, 6, 10));

        assertThat(res.periodStart()).isEqualTo(periodStart);
        assertThat(res.periodEnd()).isEqualTo(periodEnd);
        assertThat(res.totalAmount()).isEqualTo(30000);
        assertThat(res.dailyAverage()).isEqualTo(30000 / 7); // 이미 끝난 주 → 경과일수=기간 전체(7일)
        assertThat(res.dailyBreakdown()).extracting(ExpenseSummaryResponse.DailyAmount::date)
                .containsExactly(LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("조회 대상 주가 아직 진행 중이면 경과일수를 '기간 시작~오늘'로만 계산한다")
    void getWeekSummary_ongoingPeriodAveragesOverElapsedDaysOnly() {
        LocalDate periodStart = LocalDate.of(2026, 6, 7);
        LocalDate periodEnd = LocalDate.of(2026, 6, 13);
        LocalDate today = LocalDate.of(2026, 6, 10); // 기간 안(수요일) — 06-07~06-10 = 4일 경과
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE, periodStart, periodEnd))
                .thenReturn(List.of(new ExpenseDailyTotal(LocalDate.of(2026, 6, 8), 8000L)));

        ExpenseSummaryResponse res = serviceAt(today).getWeekSummary(OWNER, today);

        assertThat(res.totalAmount()).isEqualTo(8000);
        assertThat(res.dailyAverage()).isEqualTo(8000 / 4);
    }

    @Test
    @DisplayName("기간 내 지출이 하나도 없으면 totalAmount/dailyAverage 모두 0이다")
    void getWeekSummary_returnsZeroWhenNoExpensesInPeriod() {
        LocalDate periodStart = LocalDate.of(2026, 6, 7);
        LocalDate periodEnd = LocalDate.of(2026, 6, 13);
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE, periodStart, periodEnd))
                .thenReturn(List.of());

        ExpenseSummaryResponse res = serviceAt(LocalDate.of(2026, 6, 20)).getWeekSummary(OWNER, LocalDate.of(2026, 6, 10));

        assertThat(res.totalAmount()).isEqualTo(0);
        assertThat(res.dailyAverage()).isEqualTo(0);
        assertThat(res.dailyBreakdown()).isEmpty();
    }

    @Test
    @DisplayName("월간 조회는 standardMonth 해당 월 전체(1일~말일)를 기간으로 잡는다")
    void getMonthSummary_returnsFirstDayToLastDayOfMonth() {
        LocalDate periodStart = LocalDate.of(2026, 6, 1);
        LocalDate periodEnd = LocalDate.of(2026, 6, 30); // 6월은 30일까지
        when(expenseRepository.sumGroupedByDate(OWNER, ExpenseStatus.ACTIVE, periodStart, periodEnd))
                .thenReturn(List.of(new ExpenseDailyTotal(LocalDate.of(2026, 6, 15), 60000L)));

        // 오늘 = 07-05 — 조회 대상 월이 이미 끝난 시점 → 경과일수 = 30일
        ExpenseSummaryResponse res = serviceAt(LocalDate.of(2026, 7, 5))
                .getMonthSummary(OWNER, YearMonth.of(2026, 6));

        assertThat(res.periodStart()).isEqualTo(periodStart);
        assertThat(res.periodEnd()).isEqualTo(periodEnd);
        assertThat(res.totalAmount()).isEqualTo(60000);
        assertThat(res.dailyAverage()).isEqualTo(60000 / 30);
    }

    // ---------- getDaySpending ----------

    @Test
    @DisplayName("getDaySpending은 리포지토리의 합계·존재 여부를 그대로 DaySpending에 담아 반환한다")
    void getDaySpending_buildsFromRepository() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        when(expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(8000);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(true);

        DaySpending result = service().getDaySpending(OWNER, date);

        assertThat(result).isEqualTo(new DaySpending(8000, true));
    }

    @Test
    @DisplayName("해당 날짜에 기록이 하나도 없으면 totalAmount=0, hasRecord=false로 구분된다")
    void getDaySpending_returnsZeroAndNoRecordWhenNothingLogged() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        when(expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(0);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(false);
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, date)).thenReturn(false);

        DaySpending result = service().getDaySpending(OWNER, date);

        assertThat(result).isEqualTo(new DaySpending(0, false));
    }

    /**
     * existsByUser_IdAndExpenseDateAndStatus를 별도 쿼리로 둔 이유: 합계는 0원지만 기록은 존재하는 별도 case를 관리하기 위함
     */
    @Test
    @DisplayName("합계가 0원이어도 hasRecord가 true면 그대로 true로 반환된다 (합계=0과 기록없음을 혼동하지 않는지 확인)")
    void getDaySpending_keepsHasRecordTrueEvenWhenTotalIsZero() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        when(expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(0);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(true);
        DaySpending result = service().getDaySpending(OWNER, date);

        assertThat(result).isEqualTo(new DaySpending(0, true));
    }

    @Test
    @DisplayName("지출 항목 없이 '오늘은 안 썼어요' 기록만 저장돼도 hasRecord=true로 반환한다")
    void getDaySpending_includesNoSpendDayInHasRecord() {
        LocalDate date = LocalDate.of(2026, 6, 5);
        when(expenseRepository.sumPriceByUserIdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(0);
        when(expenseRepository.existsByUser_IdAndExpenseDateAndStatus(OWNER, date, ExpenseStatus.ACTIVE)).thenReturn(false);
        when(noSpendDayRepository.existsByUser_IdAndRecordDate(OWNER, date)).thenReturn(true);

        DaySpending result = service().getDaySpending(OWNER, date);

        assertThat(result).isEqualTo(new DaySpending(0, true));
    }

    // ---------- fixtures ----------

    private static User user(Long id) {
        User user = User.createLocalUser("user" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "createdAt", ACCOUNT_CREATED_AT); // 픽스처용 고정 가입 시각(lastUpdated 로직은 더 이상 이 값을 fallback으로 쓰지 않음)
        return user;
    }

    /** 이름/금액/날짜는 테스트마다 안 중요해서 고정값으로 통일 — 필요한 케이스만 category/customCategory/emotion/customEmotion을 바꿔 받는다. */
    private static Expense expenseOf(Long ownerId, ExpenseCategory category, String customCategory,
                                     ExpenseEmotion emotion, String customEmotion) {
        Expense expense = Expense.of("스타벅스", 5000, category, emotion, LocalDate.of(2026, 6, 5), user(ownerId));
        expense.assignCustomCategory(customCategory);
        expense.assignCustomEmotion(customEmotion);
        return expense;
    }
}