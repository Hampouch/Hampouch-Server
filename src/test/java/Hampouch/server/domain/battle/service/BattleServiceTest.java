package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.*;
import Hampouch.server.domain.battle.entity.Battle;
import Hampouch.server.domain.battle.entity.BattleParticipant;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.repository.BattleParticipantRepository;
import Hampouch.server.domain.battle.repository.BattleRepository;
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
import static org.mockito.ArgumentMatchers.any;
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
    UserRepository userRepository;
    @Mock
    BattleCodeGenerator battleCodeGenerator;

    private BattleService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new BattleService(battleRepository, battleParticipantRepository, userRepository, battleCodeGenerator, clock);
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
    @DisplayName("startDate가 오늘이면 통과한다 — 과거만 막고 오늘은 막지 않는다")
    void create_allowsStartDateToday() {
        when(userRepository.getReferenceById(OWNER)).thenReturn(user(OWNER));
        when(battleCodeGenerator.generate()).thenReturn("ABCD1234");
        when(battleRepository.save(any(Battle.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBattleResponse res = serviceAt(LocalDate.of(2026, 7, 10))
                .create(OWNER, request(4, 7, LocalDate.of(2026, 7, 10)));

        assertThat(res.startDate()).isEqualTo(LocalDate.of(2026, 7, 10));
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
    @DisplayName("ONGOING 배틀은 현재 today/total 실시간 집계가 없어 참가자 목록이 빈 리스트로 나간다 (③에서 구현 예정)")
    void getMyBattles_mapsOngoingWithEmptyParticipants() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        battle.start();
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle);
        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        BattleSummary.Ongoing summary = (BattleSummary.Ongoing) res.battles().get(0);
        assertThat(summary.participants()).isEmpty();
    }

    @Test
    @DisplayName("TERMINATED 배틀은 승자 요약 카드로 매핑된다 (winnerNickname은 현재 스텁 null)")
    void getMyBattles_mapsTerminated() {
        Battle battle = Battle.of("ABCD1234", "짠테크 배틀", 4, 7, LocalDate.of(2026, 8, 1), "치킨 사주기", user(OWNER));
        battle.start();
        battle.terminate(user(2L));
        BattleParticipant participation = BattleParticipant.of(user(OWNER), battle);
        when(battleParticipantRepository.findMyParticipations(OWNER, null)).thenReturn(List.of(participation));

        BattleListResponse res = serviceAt(LocalDate.of(2026, 7, 1)).getMyBattles(OWNER, null);

        assertThat(res.battles().get(0)).isInstanceOf(BattleSummary.Terminated.class);
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
}
