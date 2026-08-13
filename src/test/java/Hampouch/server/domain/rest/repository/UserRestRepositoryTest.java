package Hampouch.server.domain.rest.repository;

import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 활성·과거 휴식 조회의 JPQL을 H2에 실제 적용해 검증 — OR 술어와 날짜 경계는
 * 쿼리 텍스트의 몫이라 목으로는 검증이 안 되고 진짜 DB가 필요하다.
 * 같은 규칙의 자바판인 UserRest.isActiveOn과 결과가 늘 일치해야 하므로(규칙이 두 언어로 존재 —
 * 한쪽만 고치면 조용히 어긋난다) 경계마다 두 판정을 나란히 확인한다.
 * (@CreatedDate 위해 Clock·Auditing 설정 import — ChallengeRepositoryTest와 동일)
 */
@DataJpaTest
@Import({ClockConfig.class, JpaAuditingConfig.class})
class UserRestRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    @Autowired
    UserRestRepository userRestRepository;

    @Test
    @DisplayName("복귀 기록이 없는 휴식은 활성 휴식으로 잡힌다")
    void findActiveOn_findsRestWithoutResume() {
        UserRest rest = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(4), 7));

        Optional<UserRest> found = userRestRepository.findActiveOn(1L, TODAY);

        assertThat(found).map(UserRest::getId).contains(rest.getId());
        assertThat(rest.isActiveOn(TODAY)).isTrue(); // 자바판 판정과 일치
    }

    @Test
    @DisplayName("내일 복귀가 예약된 휴식은 오늘 기준으로 아직 활성 휴식으로 잡힌다")
    void findActiveOn_findsRestBookedForTomorrow() {
        UserRest rest = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(4), 7));
        rest.resume(TODAY.plusDays(1)); // 복귀 팝업에서 "내일부터"
        userRestRepository.save(rest);

        Optional<UserRest> found = userRestRepository.findActiveOn(1L, TODAY);

        assertThat(found).isPresent();
        assertThat(rest.isActiveOn(TODAY)).isTrue(); // 자바판 판정과 일치
    }

    @Test
    @DisplayName("휴식 시작일부터 복귀 전날까지는 휴식 정보를 반환하며, 복귀 당일부터는 휴식으로 조회하지 않는다.")
    void findContainingDate_usesStartInclusiveResumeExclusiveRange() {
        LocalDate restStartDate = TODAY.minusDays(4);
        UserRest rest = userRestRepository.save(UserRest.start(1L, restStartDate, 7));
        rest.resume(TODAY);
        userRestRepository.save(rest);

        assertThat(userRestRepository.findContainingDate(1L, restStartDate))
                .map(UserRest::getId).contains(rest.getId());
        assertThat(userRestRepository.findContainingDate(1L, TODAY.minusDays(1)))
                .map(UserRest::getId).contains(rest.getId());
        assertThat(userRestRepository.findContainingDate(1L, restStartDate.minusDays(1))).isEmpty();
        assertThat(userRestRepository.findContainingDate(1L, TODAY)).isEmpty();
        assertThat(rest.isActiveOn(restStartDate.minusDays(1))).isFalse();
    }

    @Test
    @DisplayName("복귀일이 기준일 당일인 휴식은 더 이상 활성 휴식이 아니다 — 복귀 당일부터 휴식 종료")
    void findActiveOn_excludesRestResumedToday() {
        UserRest rest = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(4), 7));
        rest.resume(TODAY); // "지금 바로" 복귀
        userRestRepository.save(rest);

        assertThat(userRestRepository.findActiveOn(1L, TODAY)).isEmpty();
        assertThat(rest.isActiveOn(TODAY)).isFalse(); // 자바판 판정과 일치
    }

    @Test
    @DisplayName("복귀 예정일이 이미 지났어도 복귀 기록이 없으면 여전히 활성 휴식이다 — 예정일 경과는 휴식을 저절로 끝내지 않고 복귀 팝업만 부른다")
    void findActiveOn_keepsRestOpenAfterPlannedDatePassed() {
        UserRest rest = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(20), 7)); // 예정일 = 13일 전

        Optional<UserRest> found = userRestRepository.findActiveOn(1L, TODAY);

        assertThat(found).isPresent();
        assertThat(rest.getPlannedResumeDate()).isBefore(TODAY); // 예정일 경과 전제가 실제로 성립
        assertThat(rest.isActiveOn(TODAY)).isTrue(); // 자바판 판정과 일치
    }

    @Test
    @DisplayName("과거에 복귀를 마친 휴식은 활성 휴식으로 잡히지 않는다")
    void findActiveOn_excludesPastRest() {
        UserRest rest = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(20), 7));
        rest.resume(TODAY.minusDays(10));
        userRestRepository.save(rest);

        assertThat(userRestRepository.findActiveOn(1L, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("남의 활성 휴식은 잡히지 않는다 — 유저 조건이 날짜 조건과 함께 걸린다")
    void findActiveOn_excludesOtherUsers() {
        userRestRepository.save(UserRest.start(2L, TODAY.minusDays(4), 7)); // 남의 활성 휴식

        assertThat(userRestRepository.findActiveOn(1L, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("과거에 끝난 휴식이 있어도 새로 시작한 활성 휴식이 잡힌다 — 휴식은 유저당 여러 번 쌓이는 이력이다")
    void findActiveOn_picksOpenAmongHistory() {
        // 한 유저의 user_rest 테이블에 닫힌 옛 행과 활성 새 행이 함께 쌓인 상황(휴식은 유저당 여러 번 반복).
        UserRest past = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(30), 7));
        past.resume(TODAY.minusDays(23)); // 23일 전 복귀 완료 → actualResumeDate 과거 = 닫힌 행
        userRestRepository.save(past);
        // 복귀 UPDATE를 새 행 INSERT보다 먼저 내보낸다 — 순서가 뒤집히면 DB에 복귀 기록 없는 행이 둘이 되어
        // uq_user_rest_unresumed_user에 걸린다(IDENTITY라 INSERT는 즉시, UPDATE는 커밋까지 미뤄지는 탓)
        userRestRepository.flush();
        UserRest open = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(2), 7)); // 복귀 없음 = 활성 행

        // "잡는 주체"는 findActiveOn 쿼리 — userId만으로 거르면 옛 닫힌 행까지 딸려와 2건(500)이 되므로,
        // 날짜 조건(actual null 또는 미래)이 닫힌 행을 배제하고 활성 행 하나만 골라야 한다.
        Optional<UserRest> found = userRestRepository.findActiveOn(1L, TODAY);

        assertThat(found).map(UserRest::getId).contains(open.getId());
    }

    @Test
    @DisplayName("같은 유저의 복귀 기록 없는 휴식이 이미 있으면 두 번째 휴식 저장을 데이터베이스 유니크 제약이 거절한다 — 시작 검사를 동시에 통과한 경쟁 요청을 막는 마지막 방어선")
    void uniqueConstraint_rejectsSecondOpenRestForSameUser() {
        userRestRepository.save(UserRest.start(1L, TODAY.minusDays(2), 7));

        assertThatThrownBy(() -> userRestRepository.saveAndFlush(UserRest.start(1L, TODAY, 3)))
                .isInstanceOf(DataIntegrityViolationException.class)
                // 서비스가 이 이름으로 "복귀 기록 없는 휴식 충돌"과 다른 제약 위반을 가르므로, 이름이 실제로 실려 오는지까지 확인한다.
                // H2는 스키마·인덱스명을 덧붙여 주므로(PUBLIC.UQ_… INDEX …) 같은지가 아니라 포함인지를 본다.
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
                .satisfies(name -> assertThat((String) name).containsIgnoringCase(UserRest.UNRESUMED_USER_UNIQUE));
    }

    @Test
    @DisplayName("유저가 다르면 복귀 기록 없는 휴식이 나란히 저장된다 — 제약은 유저 단위로만 묶는다")
    void uniqueConstraint_allowsOpenRestsAcrossUsers() {
        userRestRepository.save(UserRest.start(1L, TODAY, 7));
        userRestRepository.saveAndFlush(UserRest.start(2L, TODAY, 7));

        assertThat(userRestRepository.findActiveOn(1L, TODAY)).isPresent();
        assertThat(userRestRepository.findActiveOn(2L, TODAY)).isPresent();
    }

    @Test
    @DisplayName("이미 복귀한 휴식의 복귀 기록을 지워 되살리려 할 때 그 사이 시작된 다른 휴식이 있으면 데이터베이스가 거절한다 — 제약은 새 행 저장뿐 아니라 기존 행 수정도 막는다")
    void uniqueConstraint_rejectsReopeningWhenAnotherRestIsOpen() {
        UserRest closed = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(5), 3));
        closed.resume(TODAY); // 복귀 완료 → 자리가 열린다
        userRestRepository.flush();
        userRestRepository.saveAndFlush(UserRest.start(1L, TODAY, 7)); // 그 자리를 새 휴식이 차지

        closed.extend(TODAY, 3); // '조금 더 쉴게요'가 복귀 기록을 지워 옛 휴식을 되살리려는 상황

        assertThatThrownBy(() -> userRestRepository.saveAndFlush(closed))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("복귀를 마친 유저가 새 휴식을 시작하는 건 데이터베이스가 막지 않는다 — 유저당 하나 규칙은 아직 복귀하지 않은 휴식에만 걸린다")
    void uniqueConstraint_freesSlotAfterResume() {
        UserRest first = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(5), 3));
        first.resume(TODAY);
        // 복귀 UPDATE를 먼저 DB로 내보낸다 — id가 IDENTITY라 아래 새 행 INSERT는 즉시 나가는데,
        // UPDATE가 그보다 늦으면 DB엔 아직 복귀 기록 없는 행이 남아 있어 제약에 걸린다(플러시 순서 함정)
        userRestRepository.flush();

        userRestRepository.saveAndFlush(UserRest.start(1L, TODAY, 7));

        assertThat(userRestRepository.findActiveOn(1L, TODAY)).isPresent();
    }
}
