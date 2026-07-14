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
@Transactional(readOnly = true)
public class ChallengeService {

    /** 한도 조정 최대 횟수(챌린지당). */
    private static final int MAX_ADJUSTMENT_COUNT = 2;

    private final ChallengeRepository challengeRepository;
    private final ChallengeDayRepository challengeDayRepository;
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
        // 알맹이(금액→todaySpent)와 존재 여부(→judgedUpTo 분기) 두 용도로 쓰여서 바로 까지 않고 Optional째 들고 있는다
        Optional<ChallengeDay> todayRow = challengeDayRepository.findByChallenge_IdAndDayDate(c.getId(), today);
        // Optional.map은 스트림 map의 "0~1개" 버전 — 값이 있으면 함수를 적용해 다시 상자에 담고, 비었으면 그대로 빈 상자.
        // orElse(0)이 마지막에 상자를 열어 값이 없으면 0을 준다(인자가 상수라 orElse, 부수효과 있는 생성이면 orElseGet).
        // 타입 흐름: Optional<ChallengeDay> --map--> Optional<Integer> --orElse--> int. 상자가 아닌 건 orElse가 연 뒤라서
        int todaySpent = todayRow.map(ChallengeDay::getSpentAmount).orElse(0);
        double usageRate = ChallengeCalculator.usageRate(todaySpent, dailyLimit);
        AlertLevel alertLevel = AlertLevel.of(usageRate);

        // 홈 집계도 결과 화면과 같은 규칙(0714 확정): 미입력일 = 0원 = 성공으로 채워 계산.
        // 단 오늘은 아직 지나는 중이라 기록이 있을 때만 포함 — 판정 완료 구간 = 시작일 ~ (오늘 기록 있으면 오늘, 없으면 어제). 종료일 넘어가면 종료일까지.
        LocalDate judgedUpTo = todayRow.isPresent() ? today : today.minusDays(1);
        if (judgedUpTo.isAfter(c.getEndDate())) {
            judgedUpTo = c.getEndDate();
        }
        ChallengeSummary s = judgedUpTo.isBefore(c.getStartDate())
                ? new ChallengeSummary(0, 0, 0, 0, 0, 0) // 시작 첫날 기록 전 — 아직 판정된 날이 없음
                : ChallengeCalculator.summarizeForResult(days, dailyLimit, c.getStartDate(), judgedUpTo);

        var view = new CurrentChallengeResponse.ChallengeView(
                c.getId(), c.getDurationDays(), c.getStartDate(), c.getEndDate(),
                c.getBudgetTotal(), c.getDailyLimit(), c.getStatus());
        var progress = new CurrentChallengeResponse.Progress(
                elapsedDays(c, today), remainingDays(c, today),
                s.successDays(), s.overDays(),
                ChallengeCalculator.currentStreakAsOf(days, c.getStartDate(), judgedUpTo), s.savedAmount());
        var consumption = new CurrentChallengeResponse.Consumption(
                todaySpent, dailyLimit - todaySpent, dailyLimit,
                usageRate, ConsumptionCharacter.of(usageRate), alertLevel);

        // 경고 카드 — GOAL_TOO_TIGHT = 3일 연속 한도 초과 시 발동(0707 확정). 오늘 사용률(alertLevel)과 무관한 별개 신호라 게이트 없음.
        // 판정 범위 = 판정 완료 구간의 trailing(0714 확정): 미기록일도 0원=성공으로 채우므로 하루 건너뛰면 연속이 끊겨 카드가 사라진다.
        // WEAK_CATEGORY_ALERT는 기준 확정(0714 PM: 전체 예산의 70% 이상 소비 시, 문구 '주의' 일괄) — 카테고리별 지출(령준 EXPENSE) 연동 후 구현.
        // TODO(디자인 확인): "카드는 위험 홈에서만 노출" 규칙이 실제 의도인지(PM_질문목록 4번).
        List<String> warningCards = new ArrayList<>();
        if (ChallengeCalculator.trailingOverStreakAsOf(days, c.getStartDate(), judgedUpTo) >= 3) {
            warningCards.add("GOAL_TOO_TIGHT");
        }

        // TODO(#7 한도 조정): usedCount 는 challenge_adjustment 행 수로 교체.
        var adjustment = new CurrentChallengeResponse.Adjustment(0, MAX_ADJUSTMENT_COUNT);

        return new CurrentChallengeResponse(view, progress, consumption, warningCards, adjustment);
    }

    /** 캘린더(해당 연·월 ∩ 챌린지 기간). */
    public CalendarResponse getCalendar(Long userId, Long challengeId, int year, int month) {
        Challenge c = loadOwned(userId, challengeId); // 존재·소유 먼저(404/403) → 그 다음 파라미터 검증
        if (month < 1 || month > 12) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }
        if (year < 1 || year > 9999) {
            // 범위 밖이면 LocalDate.of 가 DateTimeException(→500)을 던지므로 여기서 400으로 막는다
            throw new CustomException(CommonErrorCode.BAD_REQUEST);
        }

        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate from = monthStart.isBefore(c.getStartDate()) ? c.getStartDate() : monthStart;
        LocalDate to = monthEnd.isAfter(c.getEndDate()) ? c.getEndDate() : monthEnd;

        List<CalendarResponse.DayView> days = from.isAfter(to)
                ? List.of()
                : challengeDayRepository.findByChallenge_IdAndDayDateBetween(challengeId, from, to).stream()
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
            c.finish(ChallengeCalculator.resultStatus(days)); // end_date 경과 → 최초 계산 시 저장(배치 없음)
        }

        // 결과는 기간 전체 기준 — 미입력일 = 0원 SUCCESS 간주(0630 확정, 명세 §4)
        ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                days, c.getDailyLimit(), c.getStartDate(), c.getEndDate());
        var period = new ResultResponse.Period(c.getStartDate(), c.getEndDate(), c.getDurationDays());
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
        // TODO(#3 give-up): 중도 포기 FAIL은 기록과 무관한 확정이라, give-up 구현 시 재계산에서 제외할 표식 필요.
        if (!c.isInProgress()) {
            c.finish(ChallengeCalculator.resultStatus(challengeDayRepository.findByChallenge_Id(challengeId)));
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
