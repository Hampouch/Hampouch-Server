package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.Challenge;
import Hampouch.server.domain.challenge.entity.ChallengeDay;
import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.challenge.entity.DayStatus;
import Hampouch.server.domain.challenge.repository.ChallengeDayRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service // 컴포넌트 스캔이 이 클래스를 찾아 스프링 빈으로 등록하게 하는 표식. 기능은 @Component와 동일하고 "서비스 계층" 의미 표시가 목적. 빈으로 등록돼야 컨트롤러에 주입되고 @Transactional 프록시도 걸린다
@RequiredArgsConstructor // final 필드들을 받는 생성자 자동 생성 — 스프링이 그 생성자로 의존성 주입(나연 common과 동일한 팀 스타일)
@Transactional(readOnly = true) // 기본 = 조회 전용 트랜잭션(플러시 생략 최적화 + 실수로 엔티티 바꿔도 DB 반영 안 되는 안전망).
// 쓰기가 필요한 메서드(create·getResult·upsertDay)만 개별 @Transactional로 덮어쓴다 — 메서드 애너테이션이 클래스 것보다 우선
public class ChallengeService {

    /** 한도 조정 최대 횟수(챌린지당). */
    private static final int MAX_ADJUSTMENT_COUNT = 2;

    private final ChallengeRepository challengeRepository;
    private final ChallengeDayRepository challengeDayRepository;
    // "지금"의 단일 출처(ClockConfig가 Asia/Seoul로 등록한 빈). 인자 없는 LocalDate.now()는 서버 OS 시간대를 타서
    // UTC 배포 환경이면 한국 아침 9시 전까지 '어제'로 계산되는 사고가 난다 + 테스트에서 시간을 고정할 방법이 없다.
    private final Clock clock;

    /** 챌린지 생성. 동시 진행 1개 가정 → 진행 중 존재 시 409. */
    @Transactional
    public CreateChallengeResponse create(Long userId, CreateChallengeRequest req) {
        if (challengeRepository.existsByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        int dailyLimit = ChallengeCalculator.dailyLimit(req.budgetTotal(), req.durationDays());
        Challenge challenge = Challenge.create(
                userId, req.durationDays(), req.startDate(), req.budgetTotal(),
                dailyLimit, req.resetByPaydayOrFalse(), req.paydayDay());
        if (req.weakCategories() != null) {
            // 같은 카테고리 중복 입력 방어 — uq_weak_category(challenge_id, category) 제약 위반으로 500 나는 것 방지
            req.weakCategories().stream().distinct().forEach(challenge::addWeakCategory);
        }
        challengeRepository.save(challenge);
        return CreateChallengeResponse.from(challenge);
    }

    /** 진행 중 챌린지 + 현황. 없으면 404. */
    public CurrentChallengeResponse getCurrent(Long userId) {
        // findBy...는 "값이 있을 수도 없을 수도 있는 상자"(Optional)를 반환.
        // orElseThrow = 상자에 값이 있으면 꺼내 주고, 비어 있으면 람다가 만든 예외를 던짐 — null 검사 if문의 한 줄 대체
        Challenge c = challengeRepository.findByUserIdAndStatus(userId, ChallengeStatus.IN_PROGRESS)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.NO_ACTIVE_CHALLENGE));

        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(c.getId());
        LocalDate today = LocalDate.now(clock);

        // 소비상태(사용률 2축) — todaySpent = 오늘 기록(없으면 0). TODO(령준 지출 연동): 출처 교체(연동 전엔 시드/POST /days 값).
        int dailyLimit = c.getDailyLimit();
        // todayRow = 오늘 날짜의 challenge_day 행(아직 기록 전이면 빈 상자).
        // 알맹이(금액→todaySpent)와 존재 여부(→lastJudgedDate 분기) 두 용도로 쓰여서 바로 까지 않고 Optional째 들고 있는다
        Optional<ChallengeDay> todayRow = challengeDayRepository.findByChallenge_IdAndDayDate(c.getId(), today);
        // Optional.map은 스트림 map의 "0~1개" 버전 — 값이 있으면 함수를 적용해 다시 상자에 담고, 비었으면 그대로 빈 상자.
        // orElse(0)이 마지막에 상자를 열어 값이 없으면 0을 준다(인자가 상수라 orElse, 부수효과 있는 생성이면 orElseGet).
        // 타입 흐름: Optional<ChallengeDay> --map--> Optional<Integer> --orElse--> int. 상자가 아닌 건 orElse가 연 뒤라서
        int todaySpent = todayRow.map(ChallengeDay::getSpentAmount).orElse(0);
        double usageRate = ChallengeCalculator.usageRate(todaySpent, dailyLimit);
        AlertLevel alertLevel = AlertLevel.of(usageRate);

        // 집계·스트릭은 판정 완료 구간(시작일 ~ lastJudgedDate)까지만 계산 — 날짜 규칙은 아래 lastJudgedDate() 참조
        LocalDate lastJudgedDate = lastJudgedDate(c, today, todayRow.isPresent());
        ChallengeSummary s = lastJudgedDate.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0) // 시작 첫날 기록 전 — 아직 판정된 날이 없음
                : ChallengeCalculator.summarizeForResult(days, dailyLimit, c.getStartDate(), lastJudgedDate);

        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), c.getDailyLimit(), c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, today), remainingDays(c, today),
                s.successDays(), s.overDays(),
                ChallengeCalculator.currentStreakAsOf(days, c.getStartDate(), lastJudgedDate), s.savedAmount());
        int todayRemaining = dailyLimit - todaySpent; // 초과 시 음수 그대로(명세 확정 — 클램프 안 함)
        var consumption = new CurrentChallengeResponse.Consumption(
                todaySpent, todayRemaining, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), alertLevel);

        // 경고 카드 — GOAL_TOO_TIGHT = 3일 연속 한도 초과 시 발동(0707 확정). 오늘 사용률(alertLevel)과 무관한 별개 신호라 게이트 없음.
        // 판정 범위 = 판정 완료 구간의 trailing(0714 확정): 미기록일도 0원=성공으로 채우므로 하루 건너뛰면 연속이 끊겨 카드가 사라진다.
        // TODO(#6 집중 카테고리, 령준 EXPENSE 연동 후): WEAK_CATEGORY_ALERT 발동 로직 구현 — 기준 확정(0714 PM: 전체 예산의 70% 이상 소비 시, 문구 '주의' 일괄). 상수 예약은 WarningCard 참조.
        // TODO(디자인 확인): 스크린디자인에서 경고 카드가 '위험 상태 홈' 시안에만 그려져 있어 해석이 갈림 —
        // (a) alertLevel=DANGER일 때만 카드 노출하는 공통 게이트가 있다 vs (b) 시안을 위험 홈에만 그렸을 뿐, 카드는 자기 조건만 충족하면 노출.
        // 현재 구현은 (b): 게이트 없이 카드별 자기 트리거만 봄 — 어제까지 3연속 초과면 오늘 사용률이 낮아도 카드가 뜬다(PM_질문목록 3번).
        List<WarningCard> warningCards = new ArrayList<>();
        if (ChallengeCalculator.isGoalTooTight(days, c.getStartDate(), lastJudgedDate)) {
            warningCards.add(WarningCard.GOAL_TOO_TIGHT);
        }

        // 한도 조정 기능(#7, POST /adjust)은 아직 미구현이라 사용 횟수는 항상 0 — 그래서 0 하드코딩(스텁).
        // 설계(ERD): 조정 1회 = challenge_adjustment 이력 행 1개(old/new 한도 기록).
        // TODO(#7): 조정 API 구현 시 usedCount를 그 챌린지의 이력 행 수 count 쿼리로 교체.
        var adjustment = new CurrentChallengeResponse.Adjustment(0, MAX_ADJUSTMENT_COUNT);

        return new CurrentChallengeResponse(view, progress, consumption, warningCards, adjustment);
    }

    /**
     * 캘린더(해당 연·월 ∩ 챌린지 기간). 유저 전체 달력이 아니라 "이 챌린지의" 달력
     * (/challenges/{id}/calendar) — 보여줄 데이터인 일별 판정 기록(성공/초과)이 챌린지 기간 안에만
     * 존재하므로, 어느 달을 요청하든 그 달에서 기록이 존재할 수 있는 범위(교집합)만 조회한다.
     */
    public CalendarResponse getCalendar(Long userId, Long challengeId, int year, int month) {
        Challenge c = loadOwned(userId, challengeId); // 존재·소유 먼저(404/403) → 그 다음 파라미터 검증
        // year·month는 클라이언트가 보내는 쿼리 파라미터 — 앱의 달 넘김 버그나 curl 직접 호출로 얼마든지 이상값이 온다.
        // 클라 검증은 UX용일 뿐, 서버가 최종 방어선.
        if (month < 1 || month > 12) {
            // 13월 등은 아래 LocalDate.of가 DateTimeException을 던져 500이 되므로 여기서 400으로 컷
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }
        if (year < 1 || year > 9999) {
            // year는 LocalDate.of가 ±999,999,999까지 허용해서 통과하지만, MySQL DATE 지원 범위(1000~9999년)를
            // 벗어나면 DB 쿼리 단계에서 터져 500이 된다. 4자리 연도 상식 범위로 여기서 400 컷
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }

        // 기록은 챌린지 기간 안에만 존재하므로, 캘린더에 보여줄 범위 = 요청한 달 ∩ 챌린지 기간.
        // (챌린지는 달 중간에 시작해 달 중간에 끝나서, 달의 앞뒤 자투리는 기간 밖일 수 있다 — 예: 7/10~7/23 챌린지의 7월)
        DateRange challengeRangeInMonth = DateRange.ofMonth(year, month)
                .intersect(new DateRange(c.getStartDate(), c.getEndDate()));

        // 겹치지 않는 달(기간 밖)을 보는 건 달 넘기기의 정상 경로라 400이 아니라 빈 캘린더(200)로 응답
        List<CalendarResponse.DayView> days = challengeRangeInMonth.isEmpty()
                ? List.of()
                : challengeDayRepository.findByChallenge_IdAndDayDateBetween(
                                challengeId, challengeRangeInMonth.start(), challengeRangeInMonth.end()).stream()
                        .sorted(Comparator.comparing(ChallengeDay::getDayDate))
                        .map(d -> new CalendarResponse.DayView(d.getDayDate(), d.getStatus(), d.getSpentAmount()))
                        .toList();
        return new CalendarResponse(challengeId, year, month, days);
    }

    /** 종료 결과. 진행 중이고 아직 end_date 전이면 409, end_date 경과면 최초 계산 시 status 확정 저장(이후 지출 수정 시 upsertDay가 재계산). */
    @Transactional
    public ResultResponse getResult(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        List<ChallengeDay> days = challengeDayRepository.findByChallenge_Id(challengeId);

        if (c.isInProgress()) {
            LocalDate today = LocalDate.now(clock);
            if (!today.isAfter(c.getEndDate())) {
                throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_ENDED);
            }
            // 기록 0건이어도 그대로 확정(0714 PM: 성공 처리) — 미입력일=0원=성공 규칙이라 전일 미입력이면 SUCCESS.
            c.applyResult(ChallengeCalculator.resultStatus(days)); // end_date 경과 → 최초 계산 시 저장(배치 없음)
        }

        // 결과는 기간 전체 기준 — 미입력일 = 0원 SUCCESS 간주(0630 확정, 명세 §4)
        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, c.getDailyLimit(), c.getStartDate(), c.getEndDate());
        var period = ResultResponse.Period.from(c);
        var summary = new ResultResponse.Summary(
                s.successDays(), s.overDays(), s.savedAmount(), s.overAmount(),
                s.maxStreak(), c.getBudgetTotal(), s.actualSpent());
        // categoryBreakdown / emotionBreakdown 은 령준 지출(EXPENSE) 의존 → 연동 확정까지 빈 배열
        return new ResultResponse(c.getId(), c.getStatus(), period, summary, List.of(), List.of());
    }

    /** 일별 지출 수신 → 그날 판정 upsert. */
    @Transactional
    public DayUpsertResponse upsertDay(Long userId, Long challengeId, DayUpsertRequest req) {
        Challenge c = loadOwned(userId, challengeId);
        if (req.date().isBefore(c.getStartDate()) || req.date().isAfter(c.getEndDate())) {
            throw new CustomException(ChallengeErrorCode.DAY_OUT_OF_RANGE);
        }
        int dailyLimit = c.getDailyLimit();
        DayStatus status = ChallengeCalculator.judge(req.spentAmount(), dailyLimit);

        // Optional 체인으로 upsert 분기 — 상자에 값이 있으면 map(기존 행 수정), 비어 있으면 orElseGet(새 행 저장)
        ChallengeDay day = challengeDayRepository.findByChallenge_IdAndDayDate(challengeId, req.date())
                .map(existing -> {
                    existing.update(req.spentAmount(), status);
                    return existing;
                })
                .orElseGet(() -> challengeDayRepository.save(
                        ChallengeDay.of(c, req.date(), req.spentAmount(), status)));

        // 종료 확정(SUCCESS/FAIL) 후 기간 내 지출을 고치면 결과도 다시 계산(0714 PM 확정) — 저장된 status를 최신 기록으로 갱신.
        // TODO(#3 give-up): 중도 포기의 FAIL은 기록에서 계산된 결과가 아니라 유저 선언으로 확정된 결과.
        // 아래 재계산이 give-up 챌린지에도 돌면 "포기했는데 기간 내 지출 한 번 고쳤더니 기록상 전부 성공이라
        // SUCCESS로 부활"하는 버그가 된다 → #3에서 종료 사유 표식(예: end_reason)을 두고 재계산에서 제외할 것.
        if (!c.isInProgress()) {
            c.applyResult(ChallengeCalculator.resultStatus(challengeDayRepository.findByChallenge_Id(challengeId)));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    private Challenge loadOwned(Long userId, Long challengeId) {
        Challenge c = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new CustomException(ChallengeErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwnedBy(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_FORBIDDEN);
        }
        return c;
    }

    /**
     * 채점(판정: 하루 지출 vs 한도 → 성공/초과)이 끝난 마지막 날. 성공/초과 집계와 스트릭은 여기까지만 센다.
     * 챌린지 마지막 날(endDate)과 다르다 — endDate는 고정된 종료일이고, 이 날짜는 오늘을 따라 움직이는
     * "어디까지 채점됐나" 커서(보통 어제, 오늘 기록을 보냈으면 오늘, 챌린지가 끝났으면 endDate에서 멈춤).
     * 규칙(0714 확정): 지나간 날은 미입력이어도 0원=성공으로 채점 완료로 본다.
     * 오늘은 아직 하루가 진행 중이라 기록을 보냈을 때만 포함 — 안 그러면 매일 아침 오늘이 미리 '성공'으로 집계된다.
     */
    private static LocalDate lastJudgedDate(Challenge c, LocalDate today, boolean todayRecorded) {
        LocalDate last = todayRecorded ? today : today.minusDays(1);
        return last.isAfter(c.getEndDate()) ? c.getEndDate() : last; // 종료일을 넘지 않게 — 둘 중 이른 날
    }

    /** 경과 일수 (시작일 당일 = 1, 기간 내로 클램프). */
    private int elapsedDays(Challenge c, LocalDate today) {
        if (today.isBefore(c.getStartDate())) {
            return 0;
        }
        LocalDate last = today.isAfter(c.getEndDate()) ? c.getEndDate() : today;
        // ChronoUnit.DAYS.between = 날짜 뺄셈(last − 시작일, 일 단위). 시작일 당일은 0이 나오므로 +1 해서 "1일차"로 만든다
        return (int) (ChronoUnit.DAYS.between(c.getStartDate(), last) + 1);
    }

    /** 남은 일수 (오늘 제외, 종료 후 0). */
    private int remainingDays(Challenge c, LocalDate today) {
        if (today.isAfter(c.getEndDate())) {
            return 0;
        }
        LocalDate base = today.isBefore(c.getStartDate()) ? c.getStartDate() : today;
        return (int) ChronoUnit.DAYS.between(base, c.getEndDate());
    }
}
