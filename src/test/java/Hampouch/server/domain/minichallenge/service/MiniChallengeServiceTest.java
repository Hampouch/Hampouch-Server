package Hampouch.server.domain.minichallenge.service;

import Hampouch.server.domain.minichallenge.dto.*;
import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeDayRepository;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeRepository;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.MiniChallengeErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 서비스 검증·상태 전이 (그날 조회·미니 추가·삭제·일별 체크 — 둘 중 하나만 400·화이트리스트·404/403·멱등 체크/해제·기간 밖·미래).
 * 리포지토리는 Mockito 목 — DB 불필요(#1 ChallengeServiceTest와 동일 구성).
 */
@ExtendWith(MockitoExtension.class)
class MiniChallengeServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    @Mock
    MiniChallengeRepository miniChallengeRepository;
    @Mock
    MiniChallengeDayRepository miniChallengeDayRepository;
    @Mock
    RecommendedMiniChallengeRepository recommendedMiniChallengeRepository;

    // 클록을 TODAY(7/10) 정오·서울로 고정한 서비스 — 이 파일 테스트는 전부 같은 "오늘" 위에서 돈다.
    // 테스트마다 날짜를 달리 넘기는 ChallengeServiceTest와 달리 여기선 항상 같아 파라미터를 없앴다(필요해지면 그때 복원).
    private MiniChallengeService service() {
        Clock clock = Clock.fixed(TODAY.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new MiniChallengeService(
                miniChallengeRepository, miniChallengeDayRepository, recommendedMiniChallengeRepository, clock);
    }

    @Test
    @DisplayName("커스텀으로 생성하면 시작일=오늘, 종료일=시작일+기간−1을 생성 시점에 한 번 계산해 굳은 값으로 저장한다(이후 재계산 없음)")
    void create_setsStartTodayAndEndSnapshot() {
        var req = new CreateMiniChallengeRequest(null,
                new CreateMiniChallengeRequest.Custom("오늘 커피 사먹지 않기", 7));

        CreateMiniChallengeResponse res = service().create(USER, req);

        assertThat(res.title()).isEqualTo("오늘 커피 사먹지 않기");
        assertThat(res.durationDays()).isEqualTo(7);
        assertThat(res.startDate()).isEqualTo(TODAY);
        assertThat(res.endDate()).isEqualTo(TODAY.plusDays(6)); // 7/10 + 7일 − 1 = 7/16
        verify(miniChallengeRepository).save(any(MiniChallenge.class));
    }

    @Test
    @DisplayName("recommendedId와 custom을 둘 다 보내면 400(MINI_INVALID_BODY)으로 거절한다 — 둘 중 하나만 허용")
    void create_rejectsBothForms() {
        var req = new CreateMiniChallengeRequest(7L,
                new CreateMiniChallengeRequest.Custom("커스텀", 7));

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_INVALID_BODY);
    }

    @Test
    @DisplayName("recommendedId와 custom이 둘 다 없어도 400(MINI_INVALID_BODY)으로 거절한다 — 둘 중 하나만 허용")
    void create_rejectsNeitherForm() {
        var req = new CreateMiniChallengeRequest(null, null);

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_INVALID_BODY);
    }

    @Test
    @DisplayName("커스텀 제목이 공백이면 400(MINI_INVALID_BODY)으로 거절한다")
    void create_rejectsBlankTitle() {
        var req = new CreateMiniChallengeRequest(null,
                new CreateMiniChallengeRequest.Custom("   ", 7));

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_INVALID_BODY);
    }

    @Test
    @DisplayName("커스텀 제목이 255자를 넘으면 DB 컬럼 제약에서 터지기 전에 400(MINI_INVALID_BODY)으로 거절한다")
    void create_rejectsTooLongTitle() {
        var req = new CreateMiniChallengeRequest(null,
                new CreateMiniChallengeRequest.Custom("가".repeat(256), 7)); // varchar(255) 초과 — 검증 없으면 INSERT에서 500

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_INVALID_BODY);
    }

    @Test
    @DisplayName("기간이 화이트리스트 {1,3,7,14,31} 밖(5일)이면 400(MINI_INVALID_DURATION)으로 거절한다")
    void create_rejectsDurationOutsideWhitelist() {
        var req = new CreateMiniChallengeRequest(null,
                new CreateMiniChallengeRequest.Custom("5일 도전", 5));

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_INVALID_DURATION);
    }

    @Test
    @DisplayName("recommendedId로 추가하면 카탈로그의 제목·기간을 복사해 시작일=오늘인 유저 소유 미니가 새로 만들어진다 — 참조가 아니라 값 복사다")
    void create_copiesFromCatalogByRecommendedId() {
        when(recommendedMiniChallengeRepository.findById(7L))
                .thenReturn(Optional.of(RecommendedMiniChallenge.of("편의점 디저트 안 먹기", 7)));
        var req = new CreateMiniChallengeRequest(7L, null);

        CreateMiniChallengeResponse res = service().create(USER, req);

        assertThat(res.title()).isEqualTo("편의점 디저트 안 먹기"); // 카탈로그 값 복사
        assertThat(res.durationDays()).isEqualTo(7);
        assertThat(res.startDate()).isEqualTo(TODAY);              // 시작일은 카탈로그가 아니라 추가한 날
        assertThat(res.endDate()).isEqualTo(TODAY.plusDays(6));
        ArgumentCaptor<MiniChallenge> captor = ArgumentCaptor.forClass(MiniChallenge.class);
        verify(miniChallengeRepository).save(captor.capture());    // 유저 소유의 새 행이 저장됨
        assertThat(captor.getValue().isOwnedBy(USER)).isTrue();
    }

    @Test
    @DisplayName("recommendedId가 카탈로그에 없으면 404(MINI_RECOMMENDED_NOT_FOUND)로 거절한다")
    void create_recommendedNotFoundWhenMissingInCatalog() {
        when(recommendedMiniChallengeRepository.findById(999L)).thenReturn(Optional.empty());
        var req = new CreateMiniChallengeRequest(999L, null);

        assertThatThrownBy(() -> service().create(USER, req))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_RECOMMENDED_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 미니를 삭제하면 404(MINI_NOT_FOUND)를 던진다")
    void delete_notFound() {
        when(miniChallengeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(USER, 99L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 미니에 접근하면 403(MINI_FORBIDDEN)을 던진다")
    void loadOwned_forbiddenWhenNotOwner() {
        MiniChallenge others = MiniChallenge.create(2L, "남의 미니", 7, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> service().delete(USER, 10L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_FORBIDDEN);
    }

    @Test
    @DisplayName("삭제하면 체크 행(자식)을 먼저 지우고 미니 행을 지운다 — FK 제약 순서")
    void delete_removesCheckRowsThenMini() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));

        service().delete(USER, 10L);

        InOrder order = inOrder(miniChallengeDayRepository, miniChallengeRepository);
        order.verify(miniChallengeDayRepository).deleteByMiniChallenge_Id(10L);
        order.verify(miniChallengeRepository).delete(mine);
    }

    @Test
    @DisplayName("미래 날짜 체크는 400(MINI_FUTURE_CHECK)으로 차단한다 — 아직 안 온 날을 미리 체크할 상황이 없어, 오면 클라 버그다")
    void check_rejectsFutureDate() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 31, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service()
                .check(USER, 10L, new MiniCheckRequest(TODAY.plusDays(1), true)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_FUTURE_CHECK);
    }

    @Test
    @DisplayName("미래이면서 기간 밖이기도 한 체크(예: 어제 끝난 미니에 내일 날짜)는 두 에러 중 MINI_FUTURE_CHECK 쪽을 돌려준다")
    void check_futureTakesPrecedenceOverOutOfRange() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 1, TODAY.minusDays(1)); // 7/9 하루짜리 — 이미 종료
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));

        // 내일(7/11)은 미래이면서 기간(7/9~7/9) 밖 — 검사 순서가 바뀌면 코드가 조용히 바뀌므로 테스트로 고정
        assertThatThrownBy(() -> service()
                .check(USER, 10L, new MiniCheckRequest(TODAY.plusDays(1), true)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_FUTURE_CHECK);
    }

    @Test
    @DisplayName("미니 기간(start~end) 밖 날짜 체크는 400(MINI_DATE_OUT_OF_RANGE)으로 거절한다")
    void check_rejectsDateOutOfRange() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(5)); // 7/5~7/11
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service()
                .check(USER, 10L, new MiniCheckRequest(TODAY.minusDays(7), true))) // 7/3 = 시작 전(과거이면서 기간 밖)
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("과거 날짜라도 기간 안이면 체크가 허용된다 — 늦은 체크·수정은 기간 안에서 자유")
    void check_allowsPastDateWithinRange() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(5)); // 7/5~7/11
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));
        when(miniChallengeDayRepository.existsByMiniChallenge_IdAndCheckDate(10L, TODAY.minusDays(4)))
                .thenReturn(false);

        MiniCheckResponse res = service()
                .check(USER, 10L, new MiniCheckRequest(TODAY.minusDays(4), true)); // 7/6 과거·기간 안

        assertThat(res.checked()).isTrue();
        assertThat(res.date()).isEqualTo(TODAY.minusDays(4));
        verify(miniChallengeDayRepository).save(any(MiniChallengeDay.class));
    }

    @Test
    @DisplayName("이미 체크된 날을 다시 체크해도 새 행을 만들지 않는다 — 기존 행 유지로 checkedAt 보존 멱등")
    void check_idempotentWhenAlreadyChecked() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));
        when(miniChallengeDayRepository.existsByMiniChallenge_IdAndCheckDate(10L, TODAY)).thenReturn(true);

        MiniCheckResponse res = service().check(USER, 10L, new MiniCheckRequest(TODAY, true));

        assertThat(res.checked()).isTrue();
        verify(miniChallengeDayRepository, never()).save(any());
    }

    @Test
    @DisplayName("해제는 행이 없어도 에러 없이 200 멱등이다")
    void uncheck_idempotentWhenRowMissing() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));
        when(miniChallengeDayRepository.deleteByMiniChallenge_IdAndCheckDate(10L, TODAY)).thenReturn(0);

        MiniCheckResponse res = service().check(USER, 10L, new MiniCheckRequest(TODAY, false));

        assertThat(res.checked()).isFalse();
        assertThat(res.date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("체크 요청에서 date를 생략하면 오늘(Clock)로 처리된다")
    void check_defaultsToTodayWhenDateOmitted() {
        MiniChallenge mine = MiniChallenge.create(USER, "내 미니", 7, TODAY.minusDays(1));
        when(miniChallengeRepository.findById(10L)).thenReturn(Optional.of(mine));
        when(miniChallengeDayRepository.existsByMiniChallenge_IdAndCheckDate(10L, TODAY)).thenReturn(false);

        service().check(USER, 10L, new MiniCheckRequest(null, true));

        ArgumentCaptor<MiniChallengeDay> captor = ArgumentCaptor.forClass(MiniChallengeDay.class);
        verify(miniChallengeDayRepository).save(captor.capture());
        assertThat(captor.getValue().getCheckDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("date를 생략하면 오늘 기준으로 조회하고, 미니가 하나도 없으면 빈 집계(0/0/0)를 준다")
    void getDaily_defaultsToTodayAndEmptySummary() {
        when(miniChallengeRepository.findByUserId(USER)).thenReturn(List.of());

        DailyMiniChallengesResponse res = service().getDaily(USER, null);

        assertThat(res.date()).isEqualTo(TODAY);
        assertThat(res.summary().checkedCount()).isZero();
        assertThat(res.summary().totalCount()).isZero();
        assertThat(res.summary().streakDays()).isZero();
        assertThat(res.items()).isEmpty();
    }

    @Test
    @DisplayName("그날 활성인 미니만 목록·totalCount에 들어간다 — 조회일 기준으로 이미 끝난 미니는 빠진다")
    void getDaily_filtersToActiveMinis() {
        MiniChallenge ended = miniWithId(1L, "끝난 미니", 3, TODAY.minusDays(9));   // 7/1~7/3
        MiniChallenge active = miniWithId(2L, "진행 미니", 7, TODAY.minusDays(1)); // 7/9~7/15
        when(miniChallengeRepository.findByUserId(USER)).thenReturn(List.of(ended, active));
        when(miniChallengeDayRepository.findByMiniChallenge_IdInAndCheckDateLessThanEqual(any(), any()))
                .thenReturn(List.of());

        DailyMiniChallengesResponse res = service().getDaily(USER, TODAY);

        assertThat(res.summary().totalCount()).isEqualTo(1);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().getFirst().miniChallengeId()).isEqualTo(2L);
        assertThat(res.items().getFirst().progressDays()).isEqualTo(2); // 7/9 시작 → 7/10 조회 = 2일차
        assertThat(res.items().getFirst().checked()).isFalse();
    }

    @Test
    @DisplayName("조회 응답의 progressDays·itemStreak·checked·streakDays는 저장된 값이 아니라 체크한 날짜 기록으로 그 자리에서 계산돼 담긴다")
    void getDaily_wiresAggregatesFromCheckRows() {
        MiniChallenge mine = miniWithId(5L, "커피 안 사먹기", 7, TODAY.minusDays(2)); // 7/8~7/14
        when(miniChallengeRepository.findByUserId(USER)).thenReturn(List.of(mine));
        when(miniChallengeDayRepository.findByMiniChallenge_IdInAndCheckDateLessThanEqual(any(), any()))
                .thenReturn(List.of(
                        MiniChallengeDay.of(mine, TODAY.minusDays(1)),
                        MiniChallengeDay.of(mine, TODAY)));

        DailyMiniChallengesResponse res = service().getDaily(USER, null);

        assertThat(res.summary().checkedCount()).isEqualTo(1);
        assertThat(res.summary().totalCount()).isEqualTo(1);
        assertThat(res.summary().streakDays()).isEqualTo(2); // 7/9·7/10 전부 체크(=이 미니 하나), 7/8 미체크로 끊김
        assertThat(res.items().getFirst().progressDays()).isEqualTo(3);
        assertThat(res.items().getFirst().itemStreak()).isEqualTo(2);
        assertThat(res.items().getFirst().checked()).isTrue();
    }

    @Test
    @DisplayName("미래 date 조회는 400(MINI_FUTURE_DATE)으로 차단한다 — 날짜 스트립이 미래로 못 가서 조회될 일이 없는 요청이다")
    void getDaily_rejectsFutureDate() {
        // 검증이 리포지토리 조회보다 앞이라 목 스터빙이 필요 없다 — 미래면 DB에 갈 일 없이 바로 400
        assertThatThrownBy(() -> service().getDaily(USER, TODAY.plusDays(1))) // 내일(7/11)
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", MiniChallengeErrorCode.MINI_FUTURE_DATE);
    }

    /**
     * 저장된 것처럼 id가 있는 미니 — getDaily가 체크 이력을 미니 id로 묶기 때문에 id가 필요하다.
     * 리포지토리를 목으로 대체해 진짜 저장(id 발급)이 없으므로 테스트 유틸로 id 필드만 채운다.
     */
    private static MiniChallenge miniWithId(Long id, String title, int durationDays, LocalDate start) {
        MiniChallenge m = MiniChallenge.create(USER, title, durationDays, start);
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }
}
