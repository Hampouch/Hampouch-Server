package Hampouch.server.domain.rest.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 휴식 1건 — 챌린지 '사이'의 공백(유저 단위, 2026-07-06 재해석). 챌린지 하위 리소스가 아니라
 * 챌린지처럼 userId만 들고 독립 테이블(user_rest)에 저장된다.
 *
 * 세 날짜의 역할:
 * - restStartDate: 휴식 시작일(생성한 날).
 * - plannedResumeDate: 복귀 예정일 = 시작일 + restDays. "더 쉬기(EXTEND)"가 이 값을 미룬다.
 *   예정일이 지나도 휴식이 저절로 끝나지 않는다 — 복귀 팝업이 뜰 뿐, 종료는 유저 응답(resume)이 만든다.
 * - actualResumeDate: 실제 복귀일. null = 아직 복귀 안 함(열린 휴식).
 *   "내일부터(TOMORROW)"를 고르면 내일 날짜가 들어가 오늘까지는 휴식 중으로 남는다 — 그래서
 *   "열려 있다"의 판정은 null 검사로 끝나지 않고 날짜 비교(isActiveOn)까지 필요하다.
 */
@Getter // 필드별 getter 자동 생성(나연 common과 동일한 팀 스타일)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자. protected = 반쪽짜리 new 차단, 정식 생성은 start()
@Entity
@Table(name = "user_rest") // 매핑될 DB 테이블 이름 — Flyway 미사용이라 ddl-auto=update가 앱 시작 때 이 이름으로 CREATE TABLE 하고, 이후 모든 SQL(INSERT/SELECT)의 대상이 된다.
// 생략해도 부트 기본 네이밍(카멜→스네이크)이 user_rest를 만들지만, ERDCloud 표기와의 일치를 자동 변환에 안 맡기고 명시(팀 관례 — users처럼 기본값과 다른 이름이 필요한 경우도 있어 항상 적는다)
@EntityListeners(AuditingEntityListener.class) // 저장 직전 @CreatedDate(createdAt) 자동 세팅 — Challenge와 동일(시각 출처는 ClockConfig의 공용 Clock)
public class UserRest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 번호 발급은 DB(auto_increment) 몫 — Challenge와 동일
    private Long id;

    /** 외부(로그인=나연)에서 오는 유저 식별. TODO(로그인 연동): @ManyToOne User 로 교체 — Challenge.userId와 같은 계획. */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate restStartDate;

    /** 복귀 예정일 = restStartDate + restDays. EXTEND가 미룬다. */
    @Column(nullable = false)
    private LocalDate plannedResumeDate;

    /** 실제 복귀일. null = 열린 휴식. 미래 날짜(내일)면 그 전날까지는 여전히 휴식 중 — 판정은 isActiveOn 참조. */
    private LocalDate actualResumeDate;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private UserRest(Long userId, LocalDate restStartDate, LocalDate plannedResumeDate) {
        this.userId = userId;
        this.restStartDate = restStartDate;
        this.plannedResumeDate = plannedResumeDate;
    }

    /**
     * 휴식 시작 통로 — 예정일 계산(오늘 + restDays, 명세 §1)을 생성과 묶어 우회 생성을 막는다.
     * 파라미터 3개 + 타입이 다 달라서 빌더가 아니라 정적 팩토리(팀 기준: 5개+ 또는 같은 타입 인접이면 빌더).
     */
    public static UserRest start(Long userId, LocalDate today, int restDays) {
        if (restDays < 1) {
            // 1 미만은 DTO @Min(1)이 400으로 거르므로 여기 도달하면 서버 코드 버그 — Challenge.applyResult와 같은 원칙으로 즉시 터뜨림
            throw new IllegalArgumentException("restDays는 1 이상이어야 한다: " + restDays);
        }
        return new UserRest(userId, today, today.plusDays(restDays));
    }

    /**
     * 복귀 확정 — NOW는 오늘, TOMORROW는 내일 날짜를 받는다. 복귀는 상태 삭제가 아니라 "종료 기록"(명세 §2).
     * TOMORROW로 이미 내일 복귀가 잡힌 휴식에 다시 호출해 날짜를 당기는 것도 허용 —
     * 아직 휴식 중(isActiveOn=true)인 동안은 마음을 바꿀 수 있다(서비스가 열린 휴식만 찾아 호출).
     */
    public void resume(LocalDate resumeDate) {
        this.actualResumeDate = resumeDate;
    }

    /**
     * 더 쉬기(EXTEND) — 복귀 예정일을 extendDays만큼 미룬다(명세 §2: 기간 재선택 → 예정일 연장).
     * actualResumeDate를 null로 되돌리는 이유(자체 결정·잠정): TOMORROW로 내일 복귀가 잡힌 상태에서
     * 연장하면, 예약된 복귀일을 지우지 않는 한 예정일만 늘고 내일 휴식이 끝나 버려 "휴식 계속"이라는
     * EXTEND의 의미와 모순이기 때문 — 연장 = 복귀 예약 취소 + 예정일 미루기.
     * 여기 도달할 때 actualResumeDate가 null이 아닌 경우는 "오늘 TOMORROW를 골라 둔 그 당일" 딱 하나다 —
     * 이 필드를 쓰는 곳은 resume(오늘 또는 내일)뿐이고 서비스는 열린 휴식(null 또는 미래)만 찾아 넘기므로,
     * 오늘·과거 복귀분은 여기 오기 전에 404로 걸러지고 "내일"도 예약 당일에만 미래로 남는다.
     */
    public void extend(int extendDays) {
        if (extendDays < 1) {
            // DTO @Min(1) 통과 후 도달 불가 — 서버 코드 버그 시 즉시 드러내는 자물쇠(start와 동일 원칙)
            throw new IllegalArgumentException("extendDays는 1 이상이어야 한다: " + extendDays);
        }
        this.plannedResumeDate = this.plannedResumeDate.plusDays(extendDays);
        this.actualResumeDate = null;
    }

    /**
     * 기준일에 이 휴식이 열려 있는가 — 복귀 기록이 없거나(actual null), 복귀일이 기준일보다 뒤(내일 복귀 예약)면 참.
     * 시작 409·복귀 404·current 휴식 분기·챌린지 생성 자동 종료가 전부 이 술어 하나를 공유한다
     * (쿼리로는 UserRestRepository.findActiveOn이 같은 조건 — 규칙은 여기가 단일 출처).
     */
    public boolean isActiveOn(LocalDate date) {
        return actualResumeDate == null || actualResumeDate.isAfter(date);
    }
}
