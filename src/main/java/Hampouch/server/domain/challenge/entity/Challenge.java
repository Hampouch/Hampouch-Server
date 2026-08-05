package Hampouch.server.domain.challenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 챌린지 1건 (온보딩 STEP2 목표 설정으로 생성).
 *
 * dailyLimit은 계산만 하지 않고 저장한다 — 판정 기준을 매번 나눗셈으로 다시 유도하지 않기 위함.
 * 단 이 값은 "지금의 한도"라 조정(#7)이 덮어쓴다. 지난 날의 판정 기준을 지키는 건 여기가 아니라
 * ChallengeDay 행마다의 dailyLimit 스냅샷이다.
 */
@Getter // 필드별 getter 자동 생성(나연 common과 동일한 팀 스타일) — boolean은 isResetByPayday() 형태
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자(Hibernate가 행→객체 복원·프록시 생성에 사용). protected = 반쪽짜리 new 차단, 정식 생성은 create()
@Entity
@Table(name = "challenge",
        uniqueConstraints = @UniqueConstraint(name = "uq_challenge_active_user", columnNames = "active_user_id"))
@EntityListeners(AuditingEntityListener.class) // 저장 직전에 끼어들어 @CreatedDate(createdAt)를 자동으로 채우는 감시자.
// 기능은 JpaAuditingConfig가 켜고, 시각은 컴퓨터 시계가 아니라 공용 Clock(Asia/Seoul, ClockConfig)에서 얻음 — 테스트에선 고정 시계로 교체 가능
public class Challenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 번호 발급은 DB(auto_increment) 몫 — 대체로 1씩 증가하지만 롤백 시 구멍 가능. 고유 식별자로만 쓰고 순서 논리엔 쓰지 말 것
    private Long id;

    /** JWT principal에서 오는 유저 id. TODO: @ManyToOne User 연관 전환은 별도 결정 대기 — 로그인 연동 후에도 id 보관을 유지 중. */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int durationDays;

    @Column(nullable = false)
    private LocalDate startDate;

    /** startDate + durationDays - 1 */
    @Column(nullable = false)
    private LocalDate endDate;

    /** 기간 내 식비 목표(원). */
    @Column(nullable = false)
    private int budgetTotal;

    /** 지금의 하루 한도. 생성 시 budgetTotal / durationDays(버림)이고, 조정(#7)을 거치면 그 결과로 바뀐다. */
    @Column(nullable = false)
    private int dailyLimit;

    /**
     * 온보딩의 "월급날 기준 리셋" 옵션. 0714 답변: 켜면 챌린지 주기가 월급날~월급날로 맞춰짐(첫 주기는 부분 기간 가능),
     * 기간형(durationDays) 챌린지는 일회성이라 월급날과 무관. 예산 리필·반복 종료 등 잔여 정의(PM_질문목록 1번)가 나올 때까지 저장만.
     */
    @Column(nullable = false)
    private boolean resetByPayday;

    /** 월급날(1~31), 옵션이라 nullable. */
    private Integer paydayDay;

    @Enumerated(EnumType.STRING) // 이름 그대로 저장. 기본값 ORDINAL(순서 번호)은 enum 중간에 상수가 끼면 기존 데이터가 통째로 오염됨
    @Column(nullable = false, length = 20)
    private ChallengeStatus status;

    /**
     * DB가 status를 보고 스스로 채우는 생성 컬럼 — 진행 중이면 userId, 아니면 NULL(NULL끼리는 유니크에 안 걸림).
     * 위의 uq_challenge_active_user 제약과 한 쌍으로 "유저당 진행 중 챌린지 1개"를 DB가 강제한다(동시 생성 경쟁의
     * 마지막 방어선 — MySQL은 조건부 유니크가 없어 이 우회를 쓴다). 값 계산은 전적으로 DB 몫이라 읽기 전용 매핑.
     * 문자열 끝에 STORED/VIRTUAL 키워드를 생략한 건 의도 — 테스트 H2가 그 키워드를 거부하고,
     * MySQL은 생략 시 VIRTUAL로 만들며 그 위 유니크 인덱스를 허용한다.
     */
    @Column(name = "active_user_id", insertable = false, updatable = false,
            columnDefinition = "bigint generated always as (case when status = 'IN_PROGRESS' then user_id end)")
    private Long activeUserId;

    /**
     * 종료 사유 표식 — null = 기록에서 계산된 판정(종료 후 지출 수정 시 재계산 대상),
     * 값이 있으면 선언 또는 자동 취소로 끝난 상태라 재계산에서 제외한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EndReason endReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 집중 카테고리는 챌린지 전속 부품(생명주기 공유) — 저장·삭제가 챌린지와 함께 전파(cascade)되고, 리스트에서 빠지면 행도 삭제(orphanRemoval)
    @OneToMany(mappedBy = "challenge", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChallengeWeakCategory> weakCategories = new ArrayList<>();

    /**
     * 챌린지 생성 통로 — Challenge.builder()...build(). dailyLimit은 호출부(서비스)에서 계산해 넘긴다
     * (한도 계산 규칙은 ChallengeCalculator가 단일 출처).
     *
     * 원래 7개 파라미터 정적 팩토리 create()였는데, 같은 int 타입이 연달아 있어(durationDays·
     * budgetTotal·dailyLimit) 호출부에서 인자 순서가 한 칸 밀려도 컴파일러가 못 잡는 위험이 커서
     * 이름 붙은 빌더로 교체(0717). @Builder를 클래스가 아니라 이 private 생성자에 붙인 이유 —
     * build()가 반드시 이 생성자를 지나므로 endDate 계산·status 초기값(IN_PROGRESS) 세팅을
     * 우회한 객체가 못 생긴다(클래스에 붙이면 필드 전체를 그대로 받는 빌더가 생겨 불변식이 뚫림).
     * 필수 필드(userId·startDate·기간·예산)를 빠뜨리면 아래 생성자 검사가 막는다 — 옵션 필드는
     * 타입 기본값(resetByPayday는 false, paydayDay는 null)이라 안 채워도 자연스럽다.
     */
    @Builder
    private Challenge(Long userId, int durationDays, LocalDate startDate, int budgetTotal,
                      int dailyLimit, boolean resetByPayday, Integer paydayDay) {
        // 빌더는 필수 필드를 빠뜨려도 build()가 컴파일된다(누락 시 참조형 null·정수형 0). 그래서 여기서 불변식을 지킨다.
        // 요청 값 자체는 CreateChallengeRequest의 @Valid가 이미 거르므로, 이 검사가 실제로 잡는 건
        // 서비스가 인자를 잘못 넘기는 서버 코드 실수 — 특히 durationDays·budgetTotal·dailyLimit처럼
        // 같은 int가 연달아 한 칸 밀리는 사고다. 클라 오류가 아니라 서버 버그라 4xx CustomException이
        // 아니라 IllegalArgumentException으로 즉시 터뜨린다(applyResult와 같은 원칙).
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("startDate는 필수입니다.");
        }
        if (durationDays < 1) {
            throw new IllegalArgumentException("durationDays는 1 이상이어야 합니다: " + durationDays);
        }
        if (budgetTotal < 0) {
            throw new IllegalArgumentException("budgetTotal은 0 이상이어야 합니다: " + budgetTotal);
        }
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("dailyLimit은 0 이상이어야 합니다: " + dailyLimit);
        }
        this.userId = userId;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = startDate.plusDays(durationDays - 1L);
        this.budgetTotal = budgetTotal;
        this.dailyLimit = dailyLimit;
        this.resetByPayday = resetByPayday;
        this.paydayDay = paydayDay;
        this.status = ChallengeStatus.IN_PROGRESS;
    }

    /**
     * 집중 카테고리 전체 교체 — 보낸 목록이 곧 최종 상태(생성·수정 공용 통로).
     * 중복을 접어 저장하는 이유는 uq_weak_category 위반이 곧 500이라서.
     *
     * 겹치는 카테고리의 기존 행을 재사용하는 건 함정 회피다(실측) — 하이버네이트가 한 번의 반영에서
     * INSERT를 고아 삭제 DELETE보다 먼저 실행해, 지웠다 같은 값을 다시 넣으면 유니크 제약에 걸린다.
     * 필드에 새 리스트를 대입하는 것도 금지 — 변경 추적이 끊긴다.
     */
    public void replaceWeakCategories(List<String> categories) {
        // 재사용할 행 찾기가 clear()보다 먼저 끝나야 한다
        List<ChallengeWeakCategory> next = categories.stream()
                .distinct()
                .map(this::reuseOrCreateWeakCategory)
                .toList();
        this.weakCategories.clear();
        this.weakCategories.addAll(next);
    }

    private ChallengeWeakCategory reuseOrCreateWeakCategory(String category) {
        return this.weakCategories.stream()
                .filter(w -> category.equals(w.getCategory()))
                .findFirst()
                .orElseGet(() -> new ChallengeWeakCategory(this, category));
    }

    /**
     * 판정 결과(SUCCESS/FAIL)를 반영 — 기간 경과 후 최초 확정(getResult)과,
     * 종료 후 지출 수정에 따른 재계산 갱신(upsertDay) 양쪽에서 쓴다(그래서 finish가 아니라 applyResult).
     * 결과가 아닌 값(IN_PROGRESS)이 오는 건 클라 요청 오류가 아니라 서버 코드 버그라,
     * 4xx용 CustomException 대신 IllegalArgumentException으로 즉시 터뜨려 잘못 호출한 코드를 드러낸다.
     */
    public void applyResult(ChallengeStatus result) {
        if (result != ChallengeStatus.SUCCESS && result != ChallengeStatus.FAIL) {
            throw new IllegalArgumentException("판정 결과는 SUCCESS/FAIL만 가능: " + result);
        }
        this.status = result;
    }

    /**
     * 중도 포기 — 유저 선언으로 즉시 FAIL 확정(0707 전체회의: 도중 중단·일시정지 없음, 그만두면 실패).
     * applyResult(계산 판정 반영)와 분리한 이유: 포기는 기록에서 나온 결과가 아니라 선언이라,
     * endReason 표식을 함께 남겨 이후 지출 수정 재계산(upsertDay)이 이 FAIL을 SUCCESS로
     * 되살리지 못하게 해야 한다(API명세_중도포기.md "재계산 부활 버그 방지").
     * endDate는 원래 목표 기간 그대로 보존(자체 결정 — 기록 유지, 필요 시 PM 확인).
     * IN_PROGRESS 검사(클라 오류 409)는 서비스 몫 — 여기까지 왔는데 아니면 서버 코드 버그라
     * 즉시 터뜨린다(applyResult가 잘못된 값에 IllegalArgumentException을 던지는 것과 같은 원칙).
     */
    public void giveUp() {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 포기할 수 있다: " + status);
        }
        this.status = ChallengeStatus.FAIL;
        this.endReason = EndReason.GIVEN_UP;
    }

    /** 3일 연속 지출 미입력으로 자동 취소한다. 사용자 중도 포기(FAIL)와 달리 지난 챌린지에는 남지 않는다. */
    public void cancelForMissingInput() {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 자동 취소할 수 있다: " + status);
        }
        this.status = ChallengeStatus.VOID;
        this.endReason = EndReason.MISSING_DAILY_INPUT;
    }

    /**
     * 목표 금액 조정(#7) 반영 — 유저가 고르는 건 목표 금액이고 하루 한도는 거기서 파생된 값이라 둘이 함께 움직인다.
     * 파생 계산은 호출부(서비스)가 ChallengeCalculator로 하고 여기선 결과만 받는다(생성 때와 같은 구조).
     * 두 값은 조정한 날부터의 것이고, 지난 날의 판정은 각 ChallengeDay의 스냅샷이 지키므로 여기서 되돌아보지 않는다.
     * 진행 중 여부·횟수 소진(409)은 서비스가 거르고, 여기 검사는 서버 코드 버그용이라
     * CustomException이 아니라 IllegalArgumentException/IllegalStateException으로 터뜨린다(giveUp과 같은 원칙).
     */
    public void adjustGoal(int newBudgetTotal, int newDailyLimit) {
        if (!isInProgress()) {
            throw new IllegalStateException("진행 중 챌린지만 목표를 조정할 수 있다: " + status);
        }
        if (newBudgetTotal < 0) {
            throw new IllegalArgumentException("budgetTotal은 0 이상이어야 합니다: " + newBudgetTotal);
        }
        if (newDailyLimit < 0) {
            throw new IllegalArgumentException("dailyLimit은 0 이상이어야 합니다: " + newDailyLimit);
        }
        this.budgetTotal = newBudgetTotal;
        this.dailyLimit = newDailyLimit;
    }

    public boolean isInProgress() {
        return status == ChallengeStatus.IN_PROGRESS;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
