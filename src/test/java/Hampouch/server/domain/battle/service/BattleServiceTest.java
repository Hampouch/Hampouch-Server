package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.dto.BattleListResponse;
import Hampouch.server.domain.battle.dto.BattleSummary;
import Hampouch.server.domain.battle.dto.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.CreateBattleResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 생성 검증 로직 + 상태별 카드 매핑 검증. 리포지토리는 Mockito 목 — DB 불필요(ExpenseServiceTest와 동일 스타일).
 */
@ExtendWith(MockitoExtension.class)
class BattleServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER = 1L;

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
    @DisplayName("durationDays가 3/7/14/31이 아니면 400(VALIDATION_ERROR)을 던진다")
    void create_rejectsInvalidDuration() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 7, 1)).create(OWNER, request(4, 10, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_ERROR);
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
}
