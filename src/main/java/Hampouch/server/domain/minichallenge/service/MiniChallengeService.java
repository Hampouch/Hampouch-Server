package Hampouch.server.domain.minichallenge.service;

import Hampouch.server.domain.minichallenge.dto.*;
import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeDayRepository;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeRepository;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import Hampouch.server.global.common.exception.domain.MiniChallengeErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MiniChallengeService {

    /**
     * 기간 화이트리스트 — 1(오늘만)·3·7·14·31일.
     * 안드가 같은 목록을 기간 탭으로 들고 있어 설정이 아니라 상수다 — 서버만 바꾸면 화면과 어긋난다.
     * 규칙의 단일 출처 — 다른 곳에 같은 목록을 복사하지 말 것.
     */
    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(1, 3, 7, 14, 31);

    private final MiniChallengeRepository miniChallengeRepository;
    private final MiniChallengeDayRepository miniChallengeDayRepository;
    private final RecommendedMiniChallengeRepository recommendedMiniChallengeRepository;
    // "지금"의 단일 출처 — 인자 없는 now()는 서버 OS 시간대를 타고 테스트에서 시각 고정도 안 된다
    private final Clock clock;

    /**
     * 그날 나의 미니 챌린지 — 요청 date(생략 시 오늘) 기준 as-of 집계.
     * 집계값은 저장하지 않고 조회 때마다 계산한다 — 저장해 두면 체크/해제 때마다 같이 고쳐야 해 어긋날 수 있다.
     * date를 String으로 받는 건 공통 핸들러에 바인딩 실패 처리가 없던 시절의 방어 — 지금은 공통 핸들러가
     * 400으로 받아 주므로 LocalDate 직접 바인딩 전환 대상(TODO #27).
     */
    public DailyMiniChallengesResponse getDaily(Long userId, String dateParam) {
        LocalDate date = parseDateOrToday(dateParam);
        // 미래 date는 400 — 화면상 미래 조회가 일어날 상황이 없고, 열어 두면 클라의 날짜 계산 버그가
        // 그럴싸한 빈 응답으로 조용히 넘어간다. 400이면 바로 드러난다.
        if (date.isAfter(LocalDate.now(clock))) {
            throw new CustomException(MiniChallengeErrorCode.MINI_FUTURE_DATE);
        }
        List<MiniChallenge> minis = miniChallengeRepository.findByUserId(userId);
        if (minis.isEmpty()) {
            return new DailyMiniChallengesResponse(
                    date, new DailyMiniChallengesResponse.Summary(0, 0, 0), List.of());
        }

        Map<Long, Set<LocalDate>> checkedDatesByMiniId = loadCheckedDatesAsOf(minis, date);
        Map<Long, MiniCheckHistory> historyByMiniId = new HashMap<>();
        for (MiniChallenge m : minis) {
            historyByMiniId.put(m.getId(), new MiniCheckHistory(
                    m.getStartDate(), m.getEndDate(),
                    checkedDatesByMiniId.getOrDefault(m.getId(), Set.of())));
        }

        // 응답 순서는 명세에 없어 id 오름차순(생성순)으로 고정 — 호출마다 순서가 흔들리지 않게
        List<DailyMiniChallengesResponse.Item> items = minis.stream()
                .filter(m -> m.isActiveOn(date))
                .sorted(Comparator.comparing(MiniChallenge::getId))
                .map(m -> {
                    MiniCheckHistory history = historyByMiniId.get(m.getId());
                    return new DailyMiniChallengesResponse.Item(
                            m.getId(), m.getTitle(), m.getDurationDays(),
                            MiniChallengeCalculator.progressDays(m.getStartDate(), date),
                            MiniChallengeCalculator.itemStreak(history, date),
                            history.isCheckedOn(date));
                })
                .toList();

        int checkedCount = (int) items.stream().filter(DailyMiniChallengesResponse.Item::checked).count();
        // 유저 스트릭은 활성 미니만이 아니라 유저의 전체 미니 이력으로 계산 — 과거 날짜엔 지금은 끝난 미니가 활성이었을 수 있다
        int streakDays = MiniChallengeCalculator.userStreakDays(List.copyOf(historyByMiniId.values()), date);

        return new DailyMiniChallengesResponse(
                date,
                new DailyMiniChallengesResponse.Summary(checkedCount, items.size(), streakDays),
                items);
    }

    @Transactional
    public CreateMiniChallengeResponse create(Long userId, CreateMiniChallengeRequest req) {
        boolean byRecommended = req.recommendedId() != null;
        boolean byCustom = req.custom() != null;
        if (byRecommended == byCustom) { // 둘 다 보냈거나 둘 다 없음 → 형태 위반
            throw new CustomException(MiniChallengeErrorCode.MINI_INVALID_BODY);
        }

        if (byRecommended) {
            // 커스텀 경로의 값 검증(비공백·길이·화이트리스트)을 여기엔 안 건다 — 카탈로그 값은 유저 입력이
            // 아니라 서버가 시드로 관리하는 데이터라, 이상하면 400이 아니라 시드 수정 사안이다.
            // "같은 추천 중복 추가 409"는 미구현 — origin 컬럼이 없어 같은 추천에서 온 미니를 식별할 수 없다.
            RecommendedMiniChallenge rec = recommendedMiniChallengeRepository.findById(req.recommendedId())
                    .orElseThrow(() -> new CustomException(MiniChallengeErrorCode.MINI_RECOMMENDED_NOT_FOUND));
            MiniChallenge mini = MiniChallenge.create(
                    userId, rec.getTitle(), rec.getDurationDays(), LocalDate.now(clock));
            miniChallengeRepository.save(mini);
            return CreateMiniChallengeResponse.from(mini);
        }

        CreateMiniChallengeRequest.Custom custom = req.custom();
        // title 필수·비공백·길이 상한(상한값은 저장 컬럼 선언과 같은 MiniChallenge.TITLE_MAX_LENGTH —
        // 여기서 안 끊으면 초과 입력이 INSERT에서 터져 클라이언트 입력 오류가 500으로 나간다),
        // durationDays 필수 — 위반은 형태 위반(MINI_INVALID_BODY)으로 자체 결정
        // (기간 "값이 이상함"과 "필드 자체가 빠짐"을 구분: 후자는 화이트리스트 이전의 폼 문제)
        if (custom.title() == null || custom.title().isBlank()
                || custom.title().length() > MiniChallenge.TITLE_MAX_LENGTH || custom.durationDays() == null) {
            throw new CustomException(MiniChallengeErrorCode.MINI_INVALID_BODY);
        }
        if (!ALLOWED_DURATIONS.contains(custom.durationDays())) {
            throw new CustomException(MiniChallengeErrorCode.MINI_INVALID_DURATION);
        }

        MiniChallenge mini = MiniChallenge.create(
                userId, custom.title(), custom.durationDays(), LocalDate.now(clock));
        miniChallengeRepository.save(mini);
        return CreateMiniChallengeResponse.from(mini);
    }

    @Transactional
    public void delete(Long userId, Long miniChallengeId) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId);
        miniChallengeDayRepository.deleteByMiniChallenge_Id(miniChallengeId); // 자식(체크 행) 먼저 — FK 제약 위반 방지
        miniChallengeRepository.delete(mini);
    }

    /**
     * 일별 체크/해제 — "토글해라"가 아니라 "이 상태로 만들어라"를 받아 재전송에 안전하다(토글은 두 번 가면 원위치).
     * checked=true는 이미 있으면 행 유지(최초 checkedAt 보존), false는 행이 없어도 그대로 200.
     */
    @Transactional
    public MiniCheckResponse check(Long userId, Long miniChallengeId, MiniCheckRequest req) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId); // 존재·소유 검사(404/403)가 날짜 검증보다 먼저
        LocalDate today = LocalDate.now(clock);
        LocalDate date = parseDateOrToday(req.date());

        // 미래 검사를 기간 검사보다 먼저 — 종료일 이후의 미래처럼 둘 다 걸릴 때 MINI_FUTURE_CHECK가
        // 우선이라는 확정을 검사 순서로 보장한다.
        if (date.isAfter(today)) {
            throw new CustomException(MiniChallengeErrorCode.MINI_FUTURE_CHECK);
        }
        if (!mini.isActiveOn(date)) { // 과거는 기간 안이면 허용 — 기간 밖만 400
            throw new CustomException(MiniChallengeErrorCode.MINI_DATE_OUT_OF_RANGE);
        }

        if (req.checked()) {
            // 알려진 한계(TODO #57): exists→save 사이에 같은 (미니, 날짜) 요청이 동시에 끼면 한쪽 INSERT가
            // 유니크 제약(uq_mini_challenge_day)에 걸려 500이 난다(중복 행 자체는 제약이 막아 데이터는 정합).
            // 여기서 try-catch로 삼키는 것만으론 안 된다 — 제약 위반 순간 트랜잭션이 rollback-only로 표시돼
            // 커밋에서 다시 터진다.
            if (!miniChallengeDayRepository.existsByMiniChallenge_IdAndCheckDate(miniChallengeId, date)) {
                miniChallengeDayRepository.save(MiniChallengeDay.of(mini, date));
            }
        } else {
            miniChallengeDayRepository.deleteByMiniChallenge_IdAndCheckDate(miniChallengeId, date); // 0건 삭제도 정상(멱등)
        }
        return new MiniCheckResponse(miniChallengeId, date, req.checked());
    }

    private MiniChallenge loadOwned(Long userId, Long miniChallengeId) {
        MiniChallenge mini = miniChallengeRepository.findById(miniChallengeId)
                .orElseThrow(() -> new CustomException(MiniChallengeErrorCode.MINI_NOT_FOUND));
        if (!mini.isOwnedBy(userId)) {
            throw new CustomException(MiniChallengeErrorCode.MINI_FORBIDDEN);
        }
        return mini;
    }

    /** date 원시값 파싱(GET 쿼리·PUT 바디 공용) — 생략(null·공백)이면 오늘, ISO(yyyy-MM-dd) 파싱 실패면 400. */
    private LocalDate parseDateOrToday(String dateParam) {
        if (dateParam == null || dateParam.isBlank()) {
            return LocalDate.now(clock);
        }
        try {
            return LocalDate.parse(dateParam);
        } catch (DateTimeParseException e) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST, e); // 전용 코드명이 없는 형식 오류라 공통 BAD_REQUEST
        }
    }

    /**
     * 미니 id별 "asOf까지 체크된 날짜" 집합 — in 절 한 번으로 조회(미니마다 낱개로 부르면 N+1).
     * asOf 상한을 안 그으면 과거 날짜 조회 화면에 그 이후의 체크가 새어 든다.
     */
    private Map<Long, Set<LocalDate>> loadCheckedDatesAsOf(List<MiniChallenge> minis, LocalDate asOf) {
        List<Long> ids = minis.stream().map(MiniChallenge::getId).toList();
        Map<Long, Set<LocalDate>> byMiniId = new HashMap<>();
        for (MiniChallengeDay row : miniChallengeDayRepository.findByMiniChallenge_IdInAndCheckDateLessThanEqual(ids, asOf)) {
            byMiniId.computeIfAbsent(row.getMiniChallenge().getId(), k -> new HashSet<>()).add(row.getCheckDate());
        }
        return byMiniId;
    }
}
