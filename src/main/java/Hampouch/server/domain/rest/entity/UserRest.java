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
 * 휴식 1건 — 챌린지 '사이'의 공백(유저 단위). 챌린지 하위가 아니라 userId만 들고 독립 테이블에 저장된다.
 *
 * 날짜 셋 중 planned와 actual은 "예정 대 실제"의 짝이고, 조기 복귀·연장으로 얼마든지 어긋나므로 컬럼이 따로 있다.
 * 예정일(planned)은 도래해도 서버가 아무것도 하지 않는 표시용 값이고, 휴식을 끝내는 건 실제 복귀일(actual)뿐이다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자. protected = 반쪽짜리 new 차단, 정식 생성은 start()
@Entity
@Table(name = "user_rest",
        uniqueConstraints = @UniqueConstraint(name = UserRest.UNRESUMED_USER_UNIQUE, columnNames = "unresumed_user_id"))
@EntityListeners(AuditingEntityListener.class)
public class UserRest {

    /** DDL과 서비스의 예외 분기가 공유하는 제약 이름 — 이름이 갈리면 위반을 409로 못 알아본다. */
    public static final String UNRESUMED_USER_UNIQUE = "uq_user_rest_unresumed_user";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // IDENTITY라 save()가 즉시 INSERT를 날린다 — 서비스가 제약 위반을 그 자리에서 잡을 수 있는 근거
    private Long id;

    /** TODO(로그인 연동): @ManyToOne User 로 교체 — Challenge.userId와 같은 계획. */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate restStartDate;

    /** 복귀 예정일 = restStartDate + restDays. EXTEND가 미룬다. */
    @Column(nullable = false)
    private LocalDate plannedResumeDate;

    /**
     * "이 날부터 휴식이 아니다"가 되는 날. null = 아직 안 정해짐(활성) · 오늘(NOW) · 내일(TOMORROW, 오늘까진 휴식).
     * 발효 전에는 엎어질 수 있다 — 아직 활성인 동안은 마지막 결정이 이긴다(NOW로 당기기·EXTEND로 취소·챌린지 생성의 자동 종료).
     * 발효 뒤엔 이 행을 고치는 모든 경로가 활성 휴식만 찾으므로(findActiveOn) 잠금 없이 저절로 불변이다.
     * ⚠️ 활성 판정에 날짜 비교(isActiveOn)가 필요한 이유가 여기 있다 — null 검사만 하면 TOMORROW를 고르는 순간
     * 휴식기 홈이 사라져 "오늘은 휴식기 홈 유지" 명세를 어긴다.
     */
    private LocalDate actualResumeDate;

    /**
     * DB가 actualResumeDate를 보고 채우는 생성 컬럼 — 복귀 기록이 없으면 userId, 있으면 NULL(NULL끼리는 유니크에 안 걸림).
     * 위 유니크와 한 쌍으로 "유저당 복귀 기록 없는 휴식 1건"을 DB가 강제한다(MySQL은 조건부 유니크가 없어 쓰는 우회).
     * isActiveOn보다 좁은 건 생성 컬럼 식에 "오늘"을 못 넣기 때문이고, 좁아도 경쟁은 다 막힌다 —
     * 내일 복귀가 예약된 행이 있으면 두 요청 다 시작 검사에서 409로 걸리고, 검사를 통과한 경쟁은 둘 다 복귀 기록이 없어 여기 걸린다.
     * STORED/VIRTUAL 키워드를 생략한 건 의도다 — H2가 그 키워드를 거부하고, MySQL은 생략하면 VIRTUAL로 만들며 그 위 유니크를 허용한다.
     */
    @Column(name = "unresumed_user_id", insertable = false, updatable = false,
            columnDefinition = "bigint generated always as (case when actual_resume_date is null then user_id end)")
    private Long unresumedUserId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private UserRest(Long userId, LocalDate restStartDate, LocalDate plannedResumeDate) {
        this.userId = userId;
        this.restStartDate = restStartDate;
        this.plannedResumeDate = plannedResumeDate;
    }

    /** 휴식 시작 통로 — 예정일 계산(오늘 + restDays)을 생성과 묶어 우회 생성을 막는다. */
    public static UserRest start(Long userId, LocalDate today, int restDays) {
        if (restDays < 1) {
            // DTO @Min(1)이 400으로 거르므로 여기 도달하면 서버 코드 버그다 — 조용히 넘기지 않고 즉시 터뜨린다
            throw new IllegalArgumentException("restDays는 1 이상이어야 한다: " + restDays);
        }
        return new UserRest(userId, today, today.plusDays(restDays));
    }

    /**
     * 복귀 확정 — 복귀는 행 삭제가 아니라 종료 기록이다. 이미 내일 복귀가 잡힌 휴식에 다시 불러 날짜를
     * 당기는 것도 허용한다(아직 휴식 중인 동안은 마음을 바꿀 수 있고, 서비스가 활성 휴식만 찾아 넘긴다).
     */
    public void resume(LocalDate resumeDate) {
        this.actualResumeDate = resumeDate;
    }

    /**
     * 연장의 기준일 = max(예정일, 오늘) — 새 예정일을 여기에 더한다. 둘을 max로 묶는 이유는 양쪽 다 깨지기 때문이다:
     * 예정일만 쓰면 늦게 답한 유저의 새 예정일이 과거로 떨어져 더 쉬기가 무동작이 되고(7/4 예정을 7/20에 3일 연장 → 7/7),
     * 오늘만 쓰면 미리 부른 연장이 휴식을 줄인다(14일 중 2일차에 3일 연장 → 7/15가 7/5로 당겨짐).
     * 서비스의 날짜 상한 가드도 이 값을 기준으로 삼아야 가드와 실제 계산이 안 갈린다.
     */
    public LocalDate extensionBaseOn(LocalDate today) {
        return plannedResumeDate.isBefore(today) ? today : plannedResumeDate;
    }

    /**
     * 더 쉬기 = 복귀 예약 취소 + 예정일 미루기(자체 결정·잠정). 복귀 기록은 보통 이미 비어 있어 되돌림이 무동작이고,
     * 실제로 일하는 경우는 "오늘 내일부터를 골라 둔" 하루뿐이다 — 그때 예약을 안 지우면 예정일만 늘고
     * 내일 휴식이 끝나 버려 "계속 쉰다"는 의미와 모순된다.
     * ⚠️ 이 null 되돌림이 행을 다시 '복귀 기록 없는 휴식'으로 만들어 유니크 제약에 걸릴 수 있다(서비스가 409로 변환).
     */
    public void extend(LocalDate today, int extendDays) {
        if (extendDays < 1) {
            // DTO @Min(1) 통과 후 도달 불가 — 서버 코드 버그를 즉시 드러내는 자물쇠(start와 동일)
            throw new IllegalArgumentException("extendDays는 1 이상이어야 한다: " + extendDays);
        }
        this.plannedResumeDate = extensionBaseOn(today).plusDays(extendDays);
        this.actualResumeDate = null;
    }

    /**
     * 기준일에 이 휴식이 활성인가 — 복귀 기록이 없거나 복귀일이 기준일보다 뒤면 참.
     * 시작 409·복귀 404·홈의 휴식 분기·챌린지 생성의 자동 종료가 이 술어 하나를 공유한다
     * (쿼리판이 findActiveOn — 한쪽만 고치면 조용히 어긋난다).
     * 경계를 "내일과 같다"가 아니라 "기준일보다 뒤"로 둔 건 이게 상태의 정의라서다 — 복귀 예약 형태가 늘어도
     * 정의는 그대로 맞고, 내일로 좁혀 두면 그날 휴식기 홈이 하루 일찍 사라지는 무음 버그가 된다.
     */
    public boolean isActiveOn(LocalDate date) {
        return actualResumeDate == null || actualResumeDate.isAfter(date);
    }
}
