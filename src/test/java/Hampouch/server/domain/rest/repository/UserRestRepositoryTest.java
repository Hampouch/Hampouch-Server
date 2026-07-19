package Hampouch.server.domain.rest.repository;

import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.global.config.ClockConfig;
import Hampouch.server.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 활성 휴식 조회(findActiveOn)의 JPQL을 H2에 실제 적용해 검증 — OR 술어와 날짜 경계는
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
        UserRest open = userRestRepository.save(UserRest.start(1L, TODAY.minusDays(2), 7)); // 복귀 없음 = 활성 행

        // "잡는 주체"는 findActiveOn 쿼리 — userId만으로 거르면 옛 닫힌 행까지 딸려와 2건(500)이 되므로,
        // 날짜 조건(actual null 또는 미래)이 닫힌 행을 배제하고 활성 행 하나만 골라야 한다.
        Optional<UserRest> found = userRestRepository.findActiveOn(1L, TODAY);

        assertThat(found).map(UserRest::getId).contains(open.getId());
    }
}
