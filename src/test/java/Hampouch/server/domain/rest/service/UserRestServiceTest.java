package Hampouch.server.domain.rest.service;

import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.rest.dto.*;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.domain.user.service.UserOperationLock;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import Hampouch.server.global.common.exception.domain.RestErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 휴식 시작·복귀의 상태 전이와 409/404 검증. 리포지토리·챌린지 서비스는 Mockito 목 — DB 불필요.
 */
@ExtendWith(MockitoExtension.class)
class UserRestServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    @Mock
    UserRestRepository userRestRepository;
    @Mock
    ChallengeService challengeService;
    @Mock
    UserOperationLock userOperationLock;

    private UserRestService serviceAt(LocalDate today) {
        Clock clock = Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL);
        return new UserRestService(userRestRepository, challengeService, userOperationLock, clock);
    }

    /** 스프링이 하이버네이트 제약 위반을 감싸 올려보내는 실제 모양 — 서비스가 제약 이름으로 원인을 가르므로 이름 없는 예외로는 검증이 안 된다. */
    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("constraint violation", new SQLException(), constraintName));
    }

    /** 오늘(7/10) 시작해 7일 뒤 복귀 예정인 활성 휴식 — 목 채번이 없어 id만 리플렉션 주입(챌린지 서비스 테스트와 같은 관례). */
    private static UserRest openRest() {
        UserRest rest = UserRest.start(USER, TODAY, 7);
        ReflectionTestUtils.setField(rest, "id", 3L);
        return rest;
    }

    @Test
    @DisplayName("휴식을 시작하면 시작일은 오늘, 복귀 예정일은 오늘에 쉬는 일수를 더한 날로 저장된다")
    void start_computesPlannedResumeDate() {
        when(challengeService.hasActiveChallenge(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.empty());
        when(userRestRepository.save(any(UserRest.class))).thenAnswer(inv -> {
            UserRest saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3L); // DB 채번 흉내
            return saved;
        });

        RestStartResponse res = serviceAt(TODAY).start(USER, new RestStartRequest(7));

        assertThat(res.restId()).isEqualTo(3L);
        assertThat(res.restStartDate()).isEqualTo(TODAY);
        assertThat(res.plannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 17)); // 7/10 + 7일
    }

    @Test
    @DisplayName("진행 중 챌린지가 있으면 휴식 시작이 409로 거절되고 휴식 행은 만들어지지 않는다 — 휴식 진입점은 결과 화면뿐")
    void start_conflictWhenChallengeActive() {
        when(challengeService.hasActiveChallenge(USER)).thenReturn(true);

        assertThatThrownBy(() -> serviceAt(TODAY).start(USER, new RestStartRequest(7)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        verify(userRestRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 휴식 중이면 또 시작할 수 없고 409로 거절된다")
    void start_conflictWhenAlreadyResting() {
        when(challengeService.hasActiveChallenge(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(openRest()));

        assertThatThrownBy(() -> serviceAt(TODAY).start(USER, new RestStartRequest(7)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", RestErrorCode.REST_ALREADY_ACTIVE);
        verify(userRestRepository, never()).save(any());
    }

    @Test
    @DisplayName("휴식 시작 요청 두 건이 쉬는 중인지 확인하는 검사를 나란히 통과해 저장에서 부딪히면, 진 쪽에 500이 아니라 이미 휴식 중이라는 409를 돌려준다")
    void start_conflictWhenUniqueConstraintRejectsConcurrentSave() {
        when(challengeService.hasActiveChallenge(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.empty());
        when(userRestRepository.save(any())).thenThrow(violationOf(UserRest.UNRESUMED_USER_UNIQUE));

        assertThatThrownBy(() -> serviceAt(TODAY).start(USER, new RestStartRequest(7)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", RestErrorCode.REST_ALREADY_ACTIVE);
    }

    @Test
    @DisplayName("저장 실패가 휴식이 겹쳐서가 아니라 다른 제약을 어긴 것이면 409로 바꾸지 않고 그대로 터뜨린다 — 원인이 다른 실패를 이미 휴식 중이라는 메시지로 덮으면 원인을 못 찾는다")
    void start_doesNotMaskOtherConstraintViolations() {
        when(challengeService.hasActiveChallenge(USER)).thenReturn(false);
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.empty());
        when(userRestRepository.save(any())).thenThrow(violationOf("fk_user_rest_user"));

        assertThatThrownBy(() -> serviceAt(TODAY).start(USER, new RestStartRequest(7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("조금 더 쉬기가 옛 휴식을 되살리는 사이 다른 휴식이 이미 시작돼 있으면, 500이 아니라 이미 휴식 중이라는 409를 돌려준다 — 연장이 복귀 기록을 지우면서 그 행이 다시 복귀 기록 없는 휴식이 되기 때문")
    void resume_extendConflictWhenAnotherRestTookTheSlot() {
        UserRest rest = openRest();
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));
        when(userRestRepository.saveAndFlush(rest)).thenThrow(violationOf(UserRest.UNRESUMED_USER_UNIQUE));

        assertThatThrownBy(() -> serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 3)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", RestErrorCode.REST_ALREADY_ACTIVE);
    }

    @Test
    @DisplayName("지금 바로 복귀를 고르면 실제 복귀일이 오늘로 기록되고 응답에도 오늘이 실린다")
    void resume_nowEndsToday() {
        UserRest rest = openRest();
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        RestResumeResponse res = serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.NOW, null));

        assertThat(rest.getActualResumeDate()).isEqualTo(TODAY); // 종료 기록(더티 체킹 저장)
        assertThat(res.restId()).isEqualTo(3L);
        assertThat(res.resumeDate()).isEqualTo(TODAY);
        assertThat(res.plannedResumeDate()).isNull(); // 이 모양엔 예정일이 안 실린다
    }

    @Test
    @DisplayName("내일부터 복귀를 고르면 실제 복귀일이 내일로 기록된다 — 오늘까지는 여전히 휴식 중으로 남는 예약")
    void resume_tomorrowEndsNextDay() {
        UserRest rest = openRest();
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        RestResumeResponse res = serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.TOMORROW, null));

        assertThat(rest.getActualResumeDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(res.resumeDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(rest.isActiveOn(TODAY)).isTrue();              // 오늘은 아직 휴식 중
        assertThat(rest.isActiveOn(TODAY.plusDays(1))).isFalse(); // 내일부터는 아님
    }

    @Test
    @DisplayName("조금 더 쉬기를 고르면 복귀 예정일이 연장 일수만큼 미뤄지고 휴식은 계속된다")
    void resume_extendPushesPlannedDate() {
        UserRest rest = openRest(); // 복귀 예정 7/17
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        RestResumeResponse res = serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 3));

        assertThat(rest.getPlannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 20)); // 예정일이 아직 안 지났으니 오늘(7/10)이 아니라 예정일 7/17이 기준 — 미리 부른 연장이 휴식을 줄이면 안 된다
        assertThat(rest.getActualResumeDate()).isNull(); // 여전히 활성 휴식
        assertThat(res.restId()).isEqualTo(3L);
        assertThat(res.plannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(res.resumeDate()).isNull(); // 이 모양엔 복귀일이 안 실린다
    }

    @Test
    @DisplayName("복귀 예정일이 지난 뒤에 조금 더 쉬기를 고르면 지나간 예정일이 아니라 오늘에 연장 일수를 더한다 — 오래 안 들어온 유저의 새 예정일이 과거로 떨어지면 복귀 팝업이 그대로 다시 떠서 더 쉬기가 아무 일도 안 한 셈이 된다")
    void resume_extendCountsFromTodayWhenPlannedDateAlreadyPassed() {
        // 7/1에 3일을 걸어 예정일이 7/4인 채로 유저가 안 들어오다가 7/20에 돌아와 다시 3일을 고른 상황
        UserRest rest = UserRest.start(USER, LocalDate.of(2026, 7, 1), 3);
        ReflectionTestUtils.setField(rest, "id", 3L);
        LocalDate comeback = LocalDate.of(2026, 7, 20);
        when(userRestRepository.findActiveOn(USER, comeback)).thenReturn(Optional.of(rest));

        RestResumeResponse res = serviceAt(comeback).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 3));

        assertThat(rest.getPlannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 23)); // 7/4 + 3일(=7/7)이 아니라 7/20 + 3일
        assertThat(res.plannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 23));
        assertThat(rest.getPlannedResumeDate()).isAfter(comeback); // 새 예정일이 미래여야 팝업이 다시 뜨지 않는다
    }

    @Test
    @DisplayName("복귀 예정일과 오늘이 같은 날에 조금 더 쉬기를 고르면 복귀 예정일이 연장 일수만큼 미뤄진다")
    void resume_extendOnPlannedResumeDate() {
        UserRest rest = openRest(); // 7/10 시작·예정일 7/17
        LocalDate plannedDay = LocalDate.of(2026, 7, 17);
        when(userRestRepository.findActiveOn(USER, plannedDay)).thenReturn(Optional.of(rest));

        serviceAt(plannedDay).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 3));

        assertThat(rest.getPlannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    @DisplayName("내일부터 복귀를 예약해 둔 상태에서 조금 더 쉬기를 고르면 예약이 취소되고 휴식이 이어진다 — 연장했는데 내일 휴식이 끝나버리는 모순 방지")
    void resume_extendCancelsBookedResume() {
        UserRest rest = openRest();
        rest.resume(TODAY.plusDays(1)); // 내일 복귀 예약 — 오늘은 아직 휴식 중이라 마음을 바꿀 수 있다
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 3));

        assertThat(rest.getActualResumeDate()).isNull(); // 내일 복귀 예약이 지워짐
        assertThat(rest.getPlannedResumeDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    @DisplayName("휴식 중이 아니면 복귀 요청은 404로 거절된다")
    void resume_notFoundWhenNotResting() {
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.NOW, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", RestErrorCode.REST_NOT_ACTIVE);
    }

    @Test
    @DisplayName("복귀 예약이 내일로 잡혀 있어도 오늘까지는 휴식 중이라 지금 바로 복귀로 바꿔 당길 수 있다")
    void resume_nowOverridesBookedTomorrow() {
        UserRest rest = openRest();
        rest.resume(TODAY.plusDays(1)); // 내일 복귀 예약
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        RestResumeResponse res = serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.NOW, null));

        assertThat(rest.getActualResumeDate()).isEqualTo(TODAY); // 내일 → 오늘로 당겨짐
        assertThat(res.resumeDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("반복 연장으로 복귀 예정일이 데이터베이스 날짜 상한(9999년)을 넘게 되는 요청은 500이 아니라 400으로 거절되고 예정일은 그대로다")
    void resume_extendRejectedWhenPlannedDateWouldOverflow() {
        UserRest rest = openRest();
        // 이미 여러 번 연장돼 예정일이 상한 직전까지 가 있는 극단 상태를 직접 만든다(요청 한 건 상한 3650으로는 도달에 수백 번 필요)
        ReflectionTestUtils.setField(rest, "plannedResumeDate", LocalDate.of(9999, 12, 1));
        when(userRestRepository.findActiveOn(USER, TODAY)).thenReturn(Optional.of(rest));

        assertThatThrownBy(() -> serviceAt(TODAY).resume(USER, new RestResumeRequest(ResumeWhen.EXTEND, 40)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.BAD_REQUEST);
        assertThat(rest.getPlannedResumeDate()).isEqualTo(LocalDate.of(9999, 12, 1)); // 거절이면 연장도 없다
    }

    @Test
    @DisplayName("쉬는 일수가 1 미만인 채 엔티티 생성까지 오면 요청 오류가 아니라 서버 버그로 보고 IllegalArgumentException이 터진다 — 1 미만은 요청 검증이 400으로 먼저 거른다")
    void entity_startRejectsNonPositiveRestDays() {
        assertThatThrownBy(() -> UserRest.start(USER, TODAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("연장 일수가 1 미만인 채 엔티티 연장까지 오면 서버 버그로 보고 IllegalArgumentException이 터진다")
    void entity_extendRejectsNonPositiveExtendDays() {
        UserRest rest = openRest();
        assertThatThrownBy(() -> rest.extend(TODAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("휴식은 시작일부터 복귀 전날까지만 활성이다")
    void entity_isActiveOnBoundary() {
        UserRest rest = UserRest.start(USER, TODAY.minusDays(1), 7);
        assertThat(rest.isActiveOn(TODAY.minusDays(2))).isFalse();
        assertThat(rest.isActiveOn(TODAY.minusDays(1))).isTrue();
        assertThat(rest.isActiveOn(TODAY)).isTrue();

        rest.resume(TODAY);
        assertThat(rest.isActiveOn(TODAY)).isFalse();
        assertThat(rest.isActiveOn(TODAY.minusDays(1))).isTrue();
    }
}
