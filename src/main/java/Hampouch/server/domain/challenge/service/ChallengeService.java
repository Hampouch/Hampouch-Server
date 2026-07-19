package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.entity.*;
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
import java.util.*;
import java.util.stream.Collectors;

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
        if (challengeRepository.existsInProgress(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        int dailyLimit = ChallengeCalculator.dailyLimit(req.budgetTotal(), req.durationDays());
        Challenge challenge = Challenge.builder()
                .userId(userId)
                .durationDays(req.durationDays())
                .startDate(req.startDate())
                .budgetTotal(req.budgetTotal())
                .dailyLimit(dailyLimit)
                .resetByPayday(req.resetByPaydayOrFalse())
                .paydayDay(req.paydayDay())
                .build();
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
        Challenge c = challengeRepository.findInProgress(userId)
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
        // 단 중도 포기(GIVEN_UP)의 FAIL은 기록에서 계산된 결과가 아니라 유저 선언이라 재계산 대상이 아니다 —
        // 제외하지 않으면 "포기했는데 기간 내 지출을 고쳤더니 기록상 전부 성공이라 SUCCESS로 부활"하는 버그가 된다(명세 주의 조항).
        // 기록 수정 자체는 포기 챌린지도 기간 내면 허용(0711 PM "종료 후 자유 수정") — 제외되는 건 status 재계산뿐.
        if (!c.isInProgress() && c.getEndReason() != EndReason.GIVEN_UP) {
            c.applyResult(ChallengeCalculator.resultStatus(challengeDayRepository.findByChallenge_Id(challengeId)));
        }

        return new DayUpsertResponse(day.getDayDate(), day.getSpentAmount(), dailyLimit, day.getStatus());
    }

    /**
     * 지난 챌린지 리스트(#4, 마이페이지) — 종료(SUCCESS/FAIL)된 것만, 최근 종료가 먼저.
     * 진행 중은 current 몫이라 제외. 한 번도 끝낸 적 없으면 빈 리스트(에러 아님).
     *
     * 조회인데 @Transactional(쓰기)인 이유 — status 확정은 배치 없이 만료 후 최초 계산 시
     * 저장하는 lazy 방식(§4)이라, 기간이 끝났는데 결과 화면(getResult)을 한 번도 안 연 챌린지는
     * DB status가 IN_PROGRESS로 남아 있다. 그대로 두면 방금 끝난 챌린지가 히스토리에서 빠지므로
     * 여기서도 같은 규칙으로 확정하고 나서 조회한다(자체 결정 — §4 lazy 확정과 일관).
     */
    @Transactional
    public ChallengeHistoryResponse getHistory(Long userId) {
        finalizeExpiredInProgress(userId);
        // 위에서 상태가 바뀐 엔티티는 아직 커밋 전이지만, JPQL 실행 직전 하이버네이트가
        // 겹치는 테이블의 변경분을 자동 플러시하므로 아래 조회에 방금 확정한 챌린지도 잡힌다
        List<Challenge> ended = challengeRepository.findByUserIdAndStatusInOrderByEndDateDescIdDesc(
                userId, List.of(ChallengeStatus.SUCCESS, ChallengeStatus.FAIL));
        if (ended.isEmpty()) {
            return new ChallengeHistoryResponse(List.of());
        }

        // 일자 기록을 챌린지마다 따로 조회하면 챌린지 수만큼 쿼리가 나간다(N+1)
        // → in절 1쿼리로 전부 가져와 메모리에서 챌린지 id별로 나눈다.
        // SQL로는 WHERE challenge_id = ? 를 N번 보내는 대신 WHERE challenge_id IN (3, 8, 12) 한 문장 —
        // 대신 여러 챌린지의 행이 한 결과셋에 섞여 오므로 아래 groupingBy가 도로 나누는 것까지가 한 세트.
        // groupingBy = SQL GROUP BY의 컬렉션판 — 분류 함수(챌린지 id)가 같은 값을 낸 요소끼리
        // List로 묶은 Map을 만든다. 기록이 0건인 챌린지는 키 자체가 안 생기므로 꺼낼 때 getOrDefault.
        // d.getChallenge().getId()는 지연 로딩 프록시라도 id만 꺼낼 땐 추가 쿼리가 없다(FK 값을 이미 들고 있음).
        Map<Long, List<ChallengeDay>> daysByChallengeId = challengeDayRepository
                .findByChallenge_IdIn(ended.stream().map(Challenge::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(d -> d.getChallenge().getId()));

        List<ChallengeHistoryResponse.Item> items = ended.stream()
                .map(c -> {
                    // 금액 요약은 결과 화면(§4)과 같은 규칙으로 조회 시 계산 — 미입력일 = 0원 지출 = 성공 간주(0630 확정)
                    ChallengeSummary s = ChallengeCalculator.summarizeForResult(
                            daysByChallengeId.getOrDefault(c.getId(), List.of()),
                            c.getDailyLimit(), c.getStartDate(), c.getEndDate());
                    return ChallengeHistoryResponse.Item.of(c, s.actualSpent(), s.savedAmount());
                })
                .toList();
        return new ChallengeHistoryResponse(items);
    }

    /**
     * 중도 포기(POST /{id}/give-up) — IN_PROGRESS를 유저 선언 FAIL로 즉시 확정(API명세_중도포기.md).
     * 검사 순서는 팀 관례대로 존재·소유(404/403) 먼저, 그 다음 상태(409) — 이미 끝났거나 기간이 지난
     * 챌린지에 누르면 409(CHALLENGE_NOT_IN_PROGRESS, #7 한도조정과 공용 예약 코드).
     *
     * 포기를 막아야 하는 상태는 둘 — (1) 저장 status가 이미 SUCCESS/FAIL, (2) status는 IN_PROGRESS지만
     * 기간이 지나 곧 확정될 챌린지(결과·히스토리 화면을 안 열어 lazy 확정이 아직 안 된 경우, §4). 둘 다
     * 막지 않으면 기간을 다 채워 SUCCESS여야 할 결과가 유저 선언 FAIL로 확정되고 — GIVEN_UP은 재계산
     * 제외라 이후 지출 수정으로도 복구 불가 — 되돌릴 수 없다. 기간 마지막 날까지는 만료가 아니라 포기 가능
     * (isAfter가 거짓 — getResult의 409 경계와 동일).
     *
     * (2)를 막을 때 만료분을 여기서 확정(IN_PROGRESS→SUCCESS/FAIL)하지 않고 만료 여부만 읽어서 판정한다
     * (나연 리뷰 반영). 이 메서드가 @Transactional이고 CustomException이 RuntimeException이라, 상태를
     * 바꾼 뒤 409를 던지면 그 확정까지 함께 롤백돼 응답은 409인데 DB는 IN_PROGRESS로 남는 모순이 생기기
     * 때문. 만료분 확정은 정상 커밋되는 조회 경로(getResult·getHistory)에 맡기고, 여기선 상태를 바꾸지
     * 않는다. 정상 포기의 전이·표식은 엔티티(giveUp)가 담당하고 저장은 더티 체킹(별도 save 없음).
     */
    @Transactional
    public GiveUpResponse giveUp(Long userId, Long challengeId) {
        Challenge c = loadOwned(userId, challengeId);
        if (!c.isInProgress() || isExpired(c)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_NOT_IN_PROGRESS);
        }
        c.giveUp();
        return GiveUpResponse.from(c);
    }

    /**
     * 기간이 끝났는데 아직 확정 전(IN_PROGRESS)인 챌린지를 §4 규칙으로 확정 — getResult의
     * 확정 블록과 같은 계산(resultStatus 단일 출처). 동시 진행 1개 가정이라 대상은 최대 1건.
     * 진행 중이거나(endDate 안 지남) 없으면 아무 일도 안 한다.
     *
     * "만료 후 미확정"이 존재하는 이유: 판정을 저절로 돌리는 배치·스케줄러가 없어서, endDate가
     * 지나도 다음 관련 조회(결과·히스토리·종료 후 지출 수정)가 올 때까지 행은 IN_PROGRESS로 남는다(lazy 확정).
     * 판정 재료(일별 기록)는 이미 다 있어 언제 계산해도 같은 답이고, 조회 경로가 항상 확정을 먼저
     * 실행하므로 낡은 상태가 응답에 노출될 일은 없다.
     */
    private void finalizeExpiredInProgress(Long userId) {
        // ifPresent: 상자에 값이 있으면 받은 람다를 그 값으로 실행하고, 비어 있으면 조용히 통과 —
        // if (opt.isPresent()) { var c = opt.get(); ... } 의 한 줄 대체. 값을 꺼내 돌려주는 게 아니라
        // (반환 void) 실행만 하는 부수효과 전용이라, 값이 필요할 땐 orElseThrow/orElse 계열을 쓴다.
        challengeRepository.findInProgress(userId).ifPresent(this::finalizeIfExpired);
    }

    /**
     * 이 챌린지가 "기간은 끝났는데 아직 확정 전(IN_PROGRESS)"이면 §4 규칙으로 그 자리에서 확정 —
     * 아니면 아무 일도 안 한다. 히스토리(유저 단위)와 포기(챌린지 단위)가 같은 확정 규칙을 쓰도록 뽑아낸
     * 단일 출처. 확정 한 줄의 세 단계: 기록 전부 로드 → 계산기 판정(OVER 1일 이상=FAIL, 잠정 — PM 질문 7)
     * → 상태 전이. applyResult 뒤 save가 없는 건 @Transactional 안 더티 체킹이 커밋 때 UPDATE를
     * 내보내기 때문(upsertDay와 동일).
     */
    private void finalizeIfExpired(Challenge c) {
        if (c.isInProgress() && isExpired(c)) {
            c.applyResult(ChallengeCalculator.resultStatus(
                    challengeDayRepository.findByChallenge_Id(c.getId())));
        }
    }

    /**
     * 기간이 지났는지(오늘이 endDate 다음 날 이후)만 판정 — 상태를 바꾸지 않는다. 포기(giveUp)에서
     * 만료된 미확정 챌린지를 상태 변경 없이 걸러내는 데 쓴다. 만료면 곧 SUCCESS/FAIL로 확정될
     * 챌린지이므로 저장 status가 아직 IN_PROGRESS여도 포기 불가. 기간 마지막 날(endDate 당일)은
     * 아직 만료가 아니다(isAfter가 거짓 — getResult·finalizeIfExpired의 경계와 동일).
     */
    private boolean isExpired(Challenge c) {
        return LocalDate.now(clock).isAfter(c.getEndDate());
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
