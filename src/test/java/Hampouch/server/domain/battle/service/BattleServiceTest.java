package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.request.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.response.*;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
import Hampouch.server.domain.expense.entity.ExpenseStatus;
import Hampouch.server.domain.expense.repository.BattleParticipantBattleSpending;
import Hampouch.server.domain.expense.repository.BattleParticipantSpending;
import Hampouch.server.domain.expense.repository.ExpenseRepository;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.BattleErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 생성 검증 로직 + 상태별 카드 매핑 + 참가 링크 조회/참가 검증 로직을 검증.
 * 리포지토리는 Mockito 목 — DB 불필요(ExpenseServiceTest와 동일 스타일).
 */
@ExtendWith(MockitoExtension.class)
class BattleServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER = 1L;
    private static final Long BATTLE_ID = 10L;

    @Mock
    BattleRepository battleRepository;
    @Mock
    BattleParticipantRepository battleParticipantRepository;
    @Mock
    ExpenseRepository expenseRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    BattleCodeGenerator battleCodeGenerator;

    private BattleService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new BattleService(battleRepository, battleParticipantRepository, expenseRepository, userRepository, battleCodeGenerator, clock);
    }

    private static User user(Long id) {
        User user = User.createLocalUser("user" + id + "@hampouch.com", "encoded", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static CreateBattleRequest request(int capacity, int durationDays, LocalDate startDate) {
        return new CreateBattleRequest("짠테크 배틀", capacity, durationDays, startDate, "치킨 사주기");
    }

    /** 참가 링크 조회/참가 테스트 공용 — status별 Battle을 만들어 BATTLE_ID를 부여한다. */
    private static Battle battleWithStatus(BattleStatus status, int capacity) {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", capacity, 7,
                LocalDate.of(2026, 8, 1), "치킨 사주기", user(99L));
        ReflectionTestUtils.setField(battle, "id", BATTLE_ID);
        switch (status) {
            case ONGOING -> battle.start();
            case TERMINATED -> {
                battle.start();
                battle.terminate(user(2L));
            }
            case CANCELLED -> battle.cancel();
            case READY -> {
                // Battle.of()가 이미 READY로 만들어줌 — 추가 전이 불필요
            }
        }
        return battle;
    }

    // ---------- create ----------

    @Test
    @DisplayName("capacity가 2 미만이면 400(INVALID_CAPACITY_RANGE)을 던진다")
    void create_rejectsCapacityBelowMin() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).create(OWNER, request(1, 7, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.INVALID_CAPACITY_RANGE);
        verifyNoInteractions(battleRepository, battleCodeGenerator);
    }

    @Test
    @DisplayName("capacity가 10 초과면 400(INVALID_CAPACITY_RANGE)을 던진다")
    void create_rejectsCapacityAboveMax() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).create(OWNER, request(11, 7, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.INVALID_CAPACITY_RANGE);
    }

    @Test
    @DisplayName("durationDays가 3/7/14/31이 아니면 400(INVALID_DURATION_DAYS)을 던진다 " +
            "— capacity/startDate와 같은 전용 코드 shape인지 확인")
    void create_rejectsInvalidDuration() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).create(OWNER, request(4, 10, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.INVALID_DURATION_DAYS);
    }

    @Test
    @DisplayName("startDate가 오늘보다 이전이면 400(INVALID_START_DATE)을 던진다")
    void create_rejectsPastStartDate() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 10)).create(OWNER, request(4, 7, LocalDate.of(2026, 7, 9))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.INVALID_START_DATE);
    }

    @Test
    @DisplayName("startDate가 오늘이면 400(INVALID_START_DATE)을 던진다(#139 리뷰 — 시작일 당일 참가 " +
            "마감 컷오프와 통일: startDate=오늘로 생성하면 아무도 참가 못 하는 죽은 배틀이 되므로 오늘도 거절)")
    void create_rejectsStartDateToday() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 10)).create(OWNER, request(4, 7, LocalDate.of(2026, 7, 10))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.INVALID_START_DATE);
        verifyNoInteractions(battleRepository, battleCodeGenerator);
    }

    @Test
    @DisplayName("startDate가 내일이면 통과한다 — 오늘·과거만 막고 내일부터는 막지 않는다")
    void create_allowsStartDateTomorrow() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(battleCodeGenerator.generate()).thenReturn("ABCD1234");
        when(battleRepository.save(any(Battle.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBattleResponse res = serviceAt(LocalDate.of(2026, 7, 10))
                .create(OWNER, request(4, 7, LocalDate.of(2026, 7, 11)));

        assertThat(res.startDate()).isEqualTo(LocalDate.of(2026, 7, 11));
    }

    @Test
    @DisplayName("생성에 성공하면 battleCode를 발급받아 저장하고, 생성자를 첫 참가자로 자동 등록한다")
    void create_generatesCodeAndRegistersCreatorAsParticipant() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(battleCodeGenerator.generate()).thenReturn("ABCD1234");
        ArgumentCaptor<Battle> battleCaptor = ArgumentCaptor.forClass(Battle.class);
        when(battleRepository.save(battleCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<BattleParticipant> participantCaptor = ArgumentCaptor.forClass(BattleParticipant.class);
        when(battleParticipantRepository.save(participantCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        CreateBattleResponse res = serviceAt(LocalDate.of(2026, 7, 1))
                .create(OWNER, request(4, 7, LocalDate.of(2026, 8, 1)));

        assertThat(battleCaptor.getValue().getBattleCode()).isEqualTo("ABCD1234");
        assertThat(participantCaptor.getValue().getBattle()).isSameAs(battleCaptor.getValue());
        assertThat(participantCaptor.getValue().getUser().getId()).isEqualTo(OWNER);
        assertThat(res.battleCode()).isEqualTo("ABCD1234");
        assertThat(res.status()).isEqualTo(BattleStatus.READY);
        assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 8, 7)); // 8/1 + (7일 - 1)
    }

    // ---------- getMyBattles ----------

    @Test
    @DisplayName("status 필터를 그대로 리포지토리에 전달한다")
    void getMyBattles_passesStatusFilterThrough() {
        when(battleParticipantRepository.findMyParticipations(OWNER, BattleStatus.READY)).thenReturn(List.of());

        serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, BattleStatus.READY);

        verify(battleParticipantRepository).findMyParticipations(OWNER, BattleStatus.READY);
    }

    @Test
    @DisplayName("READY 배틀은 capacity와 joinedCount(참가자 수)가 함께 담긴 카드로 매핑된다")
    void getMyBattles_mapsReadyWithJoinedCount() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        ReflectionTestUtils.setField(battle, "id", 10L);
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle);
        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));
        when(battleParticipantRepository.countByBattle_Id(10L)).thenReturn(3);

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        BattleSummary.Ready summary = (BattleSummary.Ready) res.battles().get(0);
        assertThat(summary.capacity()).isEqualTo(4);
        assertThat(summary.joinedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("ONGOING 배틀은 참가자별 today/total 실시간 집계 카드로 매핑된다 — 아직 지출이 없으면 0원으로 채워진다")
    void getMyBattles_mapsOngoingWithAggregatedParticipants() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        ReflectionTestUtils.setField(battle, "id", 10L);
        battle.start();
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle);
        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));
        when(battleParticipantRepository.findByBattle_IdInWithUser(List.of(10L))).thenReturn(List.of(participation));
        when(expenseRepository.sumTodayAndTotalByBattleIds(eq(List.of(10L)), any(), any())).thenReturn(List.of());

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        BattleSummary.Ongoing summary = (BattleSummary.Ongoing) res.battles().getFirst();
        assertThat(summary.participants()).hasSize(1);
        assertThat(summary.participants().getFirst().todayAmount()).isZero();
        assertThat(summary.participants().getFirst().totalAmount()).isZero();
    }

    @Test
    @DisplayName("ONGOING 배틀이 여러 개여도 참가자 조회·지출 집계 쿼리는 배틀 ID 목록 기준으로 딱 한 번씩만 나간다 " +
            "(2026-08-11, PR #128 리뷰 반영 — 원래는 ONGOING 배틀마다 쿼리 2개씩 추가되던 N+1이었음)")
    void getMyBattles_batchesOngoingQueriesAcrossMultipleBattles() {
        Battle battleA = Battle.of("AAAA0001", "배틀A", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        Battle battleB = Battle.of("BBBB0002", "배틀B", 4, 7, LocalDate.of(2026, 7, 1), "커피 사기", user(OWNER));
        ReflectionTestUtils.setField(battleA, "id", 10L);
        ReflectionTestUtils.setField(battleB, "id", 20L);
        battleA.start();
        battleB.start();
        BattleParticipant participationA = BattleParticipant.of(user(OWNER), battleA);
        BattleParticipant participationB = BattleParticipant.of(user(OWNER), battleB);
        when(battleParticipantRepository.findMyParticipations(OWNER, null))
                .thenReturn(List.of(participationA, participationB));
        when(battleParticipantRepository.findByBattle_IdInWithUser(List.of(10L, 20L)))
                .thenReturn(List.of(participationA, participationB));
        when(expenseRepository.sumTodayAndTotalByBattleIds(eq(List.of(10L, 20L)), any(), any()))
                .thenReturn(List.of(
                        new BattleParticipantBattleSpending(10L, OWNER, 0, 1000),
                        new BattleParticipantBattleSpending(20L, OWNER, 0, 2000)));

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getMyBattles(OWNER, null);

        assertThat(res.battles()).hasSize(2);
        BattleSummary.Ongoing summaryA = (BattleSummary.Ongoing) res.battles().stream()
                .filter(b -> b.battleId().equals(10L)).findFirst().orElseThrow();
        BattleSummary.Ongoing summaryB = (BattleSummary.Ongoing) res.battles().stream()
                .filter(b -> b.battleId().equals(20L)).findFirst().orElseThrow();
        assertThat(summaryA.participants().getFirst().totalAmount()).isEqualTo(1000L);
        assertThat(summaryB.participants().getFirst().totalAmount()).isEqualTo(2000L);
        // 배틀이 2개인데도 참가자 조회·지출 집계 쿼리는 각각 딱 1번만 — 배틀 개수만큼 늘어나지 않는다.
        verify(battleParticipantRepository, times(1)).findByBattle_IdInWithUser(anyList());
        verify(expenseRepository, times(1)).sumTodayAndTotalByBattleIds(anyList(), any(), any());
        verify(battleParticipantRepository, never()).findByBattle_IdWithUser(any());
        verify(expenseRepository, never()).sumTodayAndTotalByUsers(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("참가 목록에 ONGOING 배틀이 하나도 없으면 배치 조회 쿼리 자체를 안 태운다")
    void getMyBattles_skipsOngoingBatchQueriesWhenNoOngoingBattles() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        ReflectionTestUtils.setField(battle, "id", 10L);
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle); // READY 그대로

        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));
        when(battleParticipantRepository.countByBattle_Id(10L)).thenReturn(1);

        serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        verify(battleParticipantRepository, never()).findByBattle_IdInWithUser(any());
        verifyNoInteractions(expenseRepository);
    }

    @Test
    @DisplayName("TERMINATED 배틀은 rank=1 스냅샷을 가진 참가자의 닉네임을 승자로 요약한다")
    void getMyBattles_mapsTerminated() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        ReflectionTestUtils.setField(battle, "id", 10L);
        battle.start();
        battle.terminate(user(2L));
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle);
        participation.finalizeResult(1, 5000); // 종료 배치가 이미 스냅샷을 박아뒀다고 가정(④ 구현 전 임시)
        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));
        when(battleParticipantRepository.findByBattle_IdWithUser(10L)).thenReturn(List.of(participation));

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        BattleSummary.Terminated summary = (BattleSummary.Terminated) res.battles().getFirst();
        assertThat(summary.winnerNickname()).isEqualTo(user(OWNER).getNickname());
    }

    // ---------- getInvitation ----------
    @Test
    @DisplayName("status=CANCELLED로 목록을 조회하면 400(VALIDATION_ERROR)으로 거절한다 " +
            "— 취소된 배틀은 목록에 노출하지 않기로 했으므로 받을 수 없는 값. 리포지토리까지 가지 않는다")
    void getMyBattles_rejectsCancelledFilter() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, BattleStatus.CANCELLED))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(battleParticipantRepository);
    }

    // validateJoinable의 우선순위 4단계(CANCELLED → ALREADY_STARTED → ALREADY_JOINED → BATTLE_FULL)를
    // 여기서 전부 검증한다 — join()도 같은 private 메서드를 재사용하므로 join 쪽에선 중복 검증하지 않는다.

    @Test
    @DisplayName("존재하지 않는 battleCode면 404(BATTLE_CODE_NOT_FOUND)를 던진다")
    void getInvitation_throwsWhenCodeNotFound() {
        when(battleRepository.findByBattleCode("ZZZZ9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ZZZZ9999"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("CANCELLED 배틀이면 400(BATTLE_CANCELLED)을 던진다 — 다른 사유보다 우선 판단")
    void getInvitation_throwsWhenCancelled() {
        Battle battle = battleWithStatus(BattleStatus.CANCELLED, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(battle));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_CANCELLED);
    }

    @Test
    @DisplayName("READY가 아니면(ONGOING/TERMINATED) 409(BATTLE_ALREADY_STARTED)를 던진다")
    void getInvitation_throwsWhenAlreadyStarted() {
        Battle ongoing = battleWithStatus(BattleStatus.ONGOING, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(ongoing));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_ALREADY_STARTED);

        Battle terminated = battleWithStatus(BattleStatus.TERMINATED, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(terminated));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_ALREADY_STARTED);
    }

    @Test
    @DisplayName("이미 참가한 유저면 409(ALREADY_JOINED)를 던진다 — 정원이 다 찼어도 이 사유가 BATTLE_FULL보다 우선")
    void getInvitation_throwsWhenAlreadyJoined() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.existsByBattle_IdAndUser_Id(BATTLE_ID, OWNER)).thenReturn(true);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.ALREADY_JOINED);
    }

    @Test
    @DisplayName("정원이 다 찼으면 409(BATTLE_FULL)를 던진다")
    void getInvitation_throwsWhenFull() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.existsByBattle_IdAndUser_Id(BATTLE_ID, OWNER)).thenReturn(false);
        when(battleParticipantRepository.countByBattle_Id(BATTLE_ID)).thenReturn(4);

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_FULL);
    }

    @Test
    @DisplayName("참가 가능한 상태면 joinedCount를 포함한 미리보기를 반환한다 (battleId는 응답에 없음)")
    void getInvitation_returnsPreviewWhenJoinable() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findByBattleCode("ABCD1234")).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.existsByBattle_IdAndUser_Id(BATTLE_ID, OWNER)).thenReturn(false);
        when(battleParticipantRepository.countByBattle_Id(BATTLE_ID)).thenReturn(2);

        BattleInvitationResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getInvitation(OWNER, "ABCD1234");

        assertThat(res.title()).isEqualTo("짠테크 배틀");
        assertThat(res.penalty()).isEqualTo("치킨 사주기");
        assertThat(res.capacity()).isEqualTo(4);
        assertThat(res.joinedCount()).isEqualTo(2);
        assertThat(res.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(res.durationDays()).isEqualTo(7); // endDate 대신 durationDays(2026-08-01 결정)
    }

    // ---------- join ----------

    @Test
    @DisplayName("존재하지 않는 battleCode면 404(BATTLE_CODE_NOT_FOUND)를 던진다 — 락 조회(findByBattleCodeForUpdate)도 동일 처리")
    void join_throwsWhenCodeNotFound() {
        when(battleRepository.findByBattleCodeForUpdate("ZZZZ9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).join(OWNER, "ZZZZ9999"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("참가 가능하면 참가자를 저장하고 battleId를 반환한다")
    void join_savesParticipantAndReturnsBattleId() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findByBattleCodeForUpdate("ABCD1234")).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.existsByBattle_IdAndUser_Id(BATTLE_ID, OWNER)).thenReturn(false);
        when(battleParticipantRepository.countByBattle_Id(BATTLE_ID)).thenReturn(2);
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        ArgumentCaptor<BattleParticipant> captor = ArgumentCaptor.forClass(BattleParticipant.class);
        when(battleParticipantRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        JoinBattleResponse response = serviceAt(LocalDate.of(2026, 7, 1)).join(OWNER, "ABCD1234");

        assertThat(response.battleId()).isEqualTo(BATTLE_ID);
        assertThat(captor.getValue().getUser().getId()).isEqualTo(OWNER);
        assertThat(captor.getValue().getBattle()).isSameAs(battle);
    }

    @Test
    @DisplayName("검증을 통과했더라도 저장 시점에 유니크 제약(uq_battle_participant) 위반이 나면 " +
            "ALREADY_JOINED로 변환한다 — challenge #31과 동일한, 동시 재요청을 막는 마지막 방어선")
    void join_convertsUniqueConstraintViolationToAlreadyJoined() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findByBattleCodeForUpdate("ABCD1234")).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.existsByBattle_IdAndUser_Id(BATTLE_ID, OWNER)).thenReturn(false);
        when(battleParticipantRepository.countByBattle_Id(BATTLE_ID)).thenReturn(2);
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(battleParticipantRepository.save(any(BattleParticipant.class)))
                .thenThrow(new DataIntegrityViolationException("uq_battle_participant"));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).join(OWNER, "ABCD1234"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.ALREADY_JOINED);
    }

    // ---------- getBattleDetail ----------

    @Test
    @DisplayName("존재하지 않는 battleId면 404(BATTLE_NOT_FOUND)를 던진다")
    void getBattleDetail_throwsWhenBattleNotFound() {
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.BATTLE_NOT_FOUND);
    }

    @Test
    @DisplayName("요청자가 참가자 목록에 없으면 403(FORBIDDEN_NOT_PARTICIPANT)을 던진다")
    void getBattleDetail_throwsWhenNotParticipant() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        BattleParticipant other = BattleParticipant.of(user(2L), battle);
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(other));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BattleErrorCode.FORBIDDEN_NOT_PARTICIPANT);
    }

    @Test
    @DisplayName("READY 배틀은 모든 참가자가 rank=null, today/total 0원으로 나가고 penaltyTargetNickname도 null이다")
    void getBattleDetail_readyReturnsZeroedRankings() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        BattleParticipant other = BattleParticipant.of(user(2L), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me, other));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants()).hasSize(2);
        assertThat(res.participants()).allSatisfy(p -> {
            assertThat(p.rank()).isNull();
            assertThat(p.todayAmount()).isZero();
            assertThat(p.totalAmount()).isZero();
            assertThat(p.isValid()).isTrue();
        });
        assertThat(res.penaltyTargetNickname()).isNull();
        verifyNoInteractions(expenseRepository);
    }

    @Test
    @DisplayName("CANCELLED 배틀도 READY와 동일하게 랭킹 없이 0/null로 나간다")
    void getBattleDetail_cancelledReturnsZeroedRankings() {
        Battle battle = battleWithStatus(BattleStatus.CANCELLED, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants().getFirst().rank()).isNull();
        assertThat(res.penaltyTargetNickname()).isNull();
    }

    @Test
    @DisplayName("ONGOING은 실시간 집계로 경쟁 순위를 매기고, 최하위 참가자를 penaltyTargetNickname으로 노출한다")
    void getBattleDetail_ongoingRanksParticipantsAndExposesPenaltyTarget() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        BattleParticipant p1 = BattleParticipant.of(user(1L), battle);
        BattleParticipant p2 = BattleParticipant.of(user(2L), battle);
        BattleParticipant p3 = BattleParticipant.of(user(3L), battle);
        BattleParticipant p4 = BattleParticipant.of(user(4L), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(p1, p2, p3, p4));
        when(expenseRepository.sumTodayAndTotalByUsers(anyList(), any(), any(), any(), any())).thenReturn(List.of(
                new BattleParticipantSpending(1L, 100, 1000),
                new BattleParticipantSpending(2L, 100, 1000), // 1위 동점
                new BattleParticipantSpending(3L, 0, 2000),
                new BattleParticipantSpending(4L, 0, 3000)    // 최하위 → 벌칙 대상
        ));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants()).extracting(BattleDetailResponse.ParticipantRanking::rank)
                .containsExactly(1, 1, 3, 4);
        assertThat(res.penaltyTargetNickname()).isEqualTo(user(4L).getNickname());
    }

    @Test
    @DisplayName("ONGOING에서 지출 집계 결과가 없는 참가자는 today/total 0원으로 채운다 " +
            "(지출이 없으므로 오히려 최상위 등수가 된다)")
    void getBattleDetail_ongoingFillsZeroForParticipantWithoutExpense() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        BattleParticipant noExpense = BattleParticipant.of(user(2L), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me, noExpense));
        when(expenseRepository.sumTodayAndTotalByUsers(anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(new BattleParticipantSpending(OWNER, 500, 3000)));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getBattleDetail(OWNER, BATTLE_ID);

        BattleDetailResponse.ParticipantRanking noExpenseRanking = res.participants().stream()
                .filter(p -> p.userId().equals(2L))
                .findFirst().orElseThrow();
        assertThat(noExpenseRanking.todayAmount()).isZero();
        assertThat(noExpenseRanking.totalAmount()).isZero();
        assertThat(noExpenseRanking.rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("ONGOING 지출 합계가 int 최대값을 넘어도 오버플로 없이 그대로 반환한다")
    void getBattleDetail_ongoingDoesNotOverflowWhenAmountExceedsIntRange() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me));
        // int였다면 랩어라운드돼 음수가 됐어야 할 값들 — 예전 (int) 캐스팅이 남아있다면 이 테스트가 실패한다.
        long hugeTodayAmount = Integer.MAX_VALUE + 500L;
        long hugeTotalAmount = Integer.MAX_VALUE + 1000L;
        when(expenseRepository.sumTodayAndTotalByUsers(anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(new BattleParticipantSpending(OWNER, hugeTodayAmount, hugeTotalAmount)));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 10)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants().getFirst().todayAmount()).isEqualTo(hugeTodayAmount);
        assertThat(res.participants().getFirst().totalAmount()).isEqualTo(hugeTotalAmount);
    }

    @Test
    @DisplayName("배틀 종료일이 지났는데 아직 ONGOING이면(④ 종료 배치 실행 전 창구) 집계 상한을 " +
            "오늘이 아니라 종료일로 clamp해서 리포지토리를 호출한다 — 종료일 다음 지출이 안 섞이도록 " +
            "(2026-08-11, PR #128 리뷰 반영: endDate로 today를 주면 종료일 이후 지출까지 포함될 수 있었음)")
    void getBattleDetail_ongoingClampsAggregationEndToBattleEndDateWhenTodayIsAfter() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4); // startDate 2026-08-01, durationDays 7 -> endDate 2026-08-07
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me));
        when(expenseRepository.sumTodayAndTotalByUsers(anyList(), any(), any(), any(), any())).thenReturn(List.of());
        LocalDate today = LocalDate.of(2026, 8, 10); // endDate(8/7)보다 3일 지남 — 종료 배치가 아직 안 돈 창구를 가정

        serviceAt(today).getBattleDetail(OWNER, BATTLE_ID);

        verify(expenseRepository).sumTodayAndTotalByUsers(
                eq(List.of(OWNER)), eq(battle.getStartDate()), eq(battle.getEndDate()), eq(today), eq(ExpenseStatus.ACTIVE));
    }

    @Test
    @DisplayName("TERMINATED는 재집계하지 않고 참가자 스냅샷(rank/totalAmount)을 그대로 읽고, " +
            "리포지토리가 반환한 순서와 무관하게 rank 오름차순으로 정렬해 반환한다 " +
            "— todayAmount는 0 고정, penaltyTargetNickname은 Battle.penaltyUser 스냅샷에서 나온다 " +
            "(2026-08-11, PR #128 리뷰 반영 — 원래는 정렬을 안 해서 리포지토리 반환 순서 그대로 나갔음)")
    void getBattleDetail_terminatedUsesSnapshot() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        User winner = user(1L);
        User loser = user(2L);
        BattleParticipant winnerParticipant = BattleParticipant.of(winner, battle);
        BattleParticipant loserParticipant = BattleParticipant.of(loser, battle);
        winnerParticipant.finalizeResult(1, 10000);
        loserParticipant.finalizeResult(2, 50000);
        battle.terminate(loser);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        // 일부러 rank와 반대 순서(loser=2등 먼저, winner=1등 나중)로 반환 — 서비스가 정렬을 안 하면
        // 이 테스트가 실패해야 한다(findByBattle_IdWithUser는 참가순이지 rank순이 아니므로 실제로 있을 수 있는 순서).
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID))
                .thenReturn(List.of(loserParticipant, winnerParticipant));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 20)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants()).extracting(BattleDetailResponse.ParticipantRanking::todayAmount)
                .containsExactly(0L, 0L);
        assertThat(res.participants()).extracting(BattleDetailResponse.ParticipantRanking::totalAmount)
                .containsExactly(10000L, 50000L);
        assertThat(res.participants()).extracting(BattleDetailResponse.ParticipantRanking::rank)
                .containsExactly(1, 2);
        assertThat(res.penaltyTargetNickname()).isEqualTo(loser.getNickname());
        verifyNoInteractions(expenseRepository);
    }

    @Test
    @DisplayName("TERMINATED인데 참가자에 rank/totalAmount 스냅샷이 없으면 데이터 정합성 예외를 던진다 " +
            "(2026-08-10, 실제 gradle test로 발견 — 원래는 int 언박싱에서 의미 불명확한 NPE가 났음)")
    void getBattleDetail_throwsWhenTerminatedParticipantMissingSnapshot() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        ReflectionTestUtils.setField(battle, "status", BattleStatus.TERMINATED); // finalizeResult() 없이 강제로 TERMINATED
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("totalAmount");
    }

    @Test
    @DisplayName("참가자 스냅샷은 있는데 Battle.penaltyUser 스냅샷만 없으면 데이터 정합성 예외를 던진다")
    void getBattleDetail_throwsWhenTerminatedWithoutPenaltyUser() {
        Battle battle = battleWithStatus(BattleStatus.ONGOING, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        me.finalizeResult(1, 10000); // 참가자 스냅샷은 정상 — battle.terminate()만 호출 안 해서 penaltyUser가 null
        ReflectionTestUtils.setField(battle, "status", BattleStatus.TERMINATED);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("penaltyUser");
    }

    @Test
    @DisplayName("탈퇴한 참가자는 닉네임이 고정 문구로 마스킹되고 avatarUrl은 null로 나간다")
    void getBattleDetail_masksDeletedParticipant() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        User deletedUser = user(2L);
        deletedUser.delete();
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        BattleParticipant deletedParticipant = BattleParticipant.of(deletedUser, battle);
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me, deletedParticipant));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID);

        BattleDetailResponse.ParticipantRanking masked = res.participants().stream()
                .filter(p -> p.userId().equals(2L))
                .findFirst().orElseThrow();
        assertThat(masked.nickname()).isEqualTo("탈퇴한 사용자");
        assertThat(masked.avatarUrl()).isNull();
    }

    @Test
    @DisplayName("무효화된(isValid=false) 참가자는 그대로 노출되고, 다른 참가자는 영향받지 않는다")
    void getBattleDetail_exposesInvalidatedParticipant() {
        Battle battle = battleWithStatus(BattleStatus.READY, 4);
        BattleParticipant me = BattleParticipant.of(user(OWNER), battle);
        BattleParticipant invalidated = BattleParticipant.of(user(2L), battle);
        invalidated.invalidate();
        when(battleRepository.findById(BATTLE_ID)).thenReturn(Optional.of(battle));
        when(battleParticipantRepository.findByBattle_IdWithUser(BATTLE_ID)).thenReturn(List.of(me, invalidated));

        BattleDetailResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getBattleDetail(OWNER, BATTLE_ID);

        assertThat(res.participants().stream().filter(p -> p.userId().equals(2L)).findFirst().orElseThrow().isValid())
                .isFalse();
        assertThat(res.participants().stream().filter(p -> p.userId().equals(OWNER)).findFirst().orElseThrow().isValid())
                .isTrue();
    }
}
