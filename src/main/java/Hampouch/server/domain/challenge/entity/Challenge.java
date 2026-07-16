package Hampouch.server.domain.challenge.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 집중 카테고리는 챌린지 전속 부품(생명주기 공유) — 저장·삭제가 챌린지와 함께 전파(cascade)되고, 리스트에서 빠지면 행도 삭제(orphanRemoval)
    @OneToMany(mappedBy = "challenge", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChallengeWeakCategory> weakCategories = new ArrayList<>();

    private Challenge(Long userId, int durationDays, LocalDate startDate, int budgetTotal,
                      int dailyLimit, boolean resetByPayday, Integer paydayDay) {
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
     * 챌린지 생성. dailyLimit은 호출부(서비스)에서 계산해 넘긴다
     * (한도 계산 규칙은 ChallengeCalculator가 단일 출처).
     *
     * 생성자 대신 이름 있는 정적 팩토리를 공개 통로로 둔 것 — 생성자가 private이라
     * 생성 경로가 여기 하나뿐이고, endDate 계산·status 초기값 세팅(private 생성자)을
     * 우회한 객체가 못 생긴다. 문법상 필수는 아니고 생성 통로를 좁히는 관례.
     */
    public static Challenge create(Long userId, int durationDays, LocalDate startDate, int budgetTotal,
                                   int dailyLimit, boolean resetByPayday, Integer paydayDay) {
        return new Challenge(userId, durationDays, startDate, budgetTotal, dailyLimit, resetByPayday, paydayDay);
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

    public boolean isInProgress() {
        return status == ChallengeStatus.IN_PROGRESS;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
