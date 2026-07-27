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
 * dailyLimit은 계산만 하지 않고 스냅샷으로 저장한다 — 나중에 목표·기간이 바뀌어도
 * 과거 판정 기준이 흔들리지 않게 하기 위함.
 */
@Getter // 필드별 getter 자동 생성(나연 common과 동일한 팀 스타일) — boolean은 isResetByPayday() 형태
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 필수 빈 생성자(Hibernate가 행→객체 복원·프록시 생성에 사용). protected = 반쪽짜리 new 차단, 정식 생성은 create()
@Entity
@Table(name = "challenge")
@EntityListeners(AuditingEntityListener.class) // 저장 직전에 끼어들어 @CreatedDate(createdAt)를 자동으로 채우는 감시자.
// 기능은 JpaAuditingConfig가 켜고, 시각은 컴퓨터 시계가 아니라 공용 Clock(Asia/Seoul, ClockConfig)에서 얻음 — 테스트에선 고정 시계로 교체 가능
public class Challenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 번호 발급은 DB(auto_increment) 몫 — 대체로 1씩 증가하지만 롤백 시 구멍 가능. 고유 식별자로만 쓰고 순서 논리엔 쓰지 말 것
    private Long id;

    /** 외부(로그인=나연)에서 오는 유저 식별. TODO(로그인 연동): @ManyToOne User 로 교체. */
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

    /** 스냅샷 = budgetTotal / durationDays (버림). */
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
     * 종료 사유 표식 — null = 기록에서 계산된 판정(종료 후 지출 수정 시 재계산 대상),
     * GIVEN_UP = 유저 선언 FAIL(재계산 제외). nullable인 이유와 확장 계획은 EndReason 주석 참조.
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

    public void addWeakCategory(String category) {
        this.weakCategories.add(new ChallengeWeakCategory(this, category));
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

    public boolean isInProgress() {
        return status == ChallengeStatus.IN_PROGRESS;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
