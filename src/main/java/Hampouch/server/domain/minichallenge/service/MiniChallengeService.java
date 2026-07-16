package Hampouch.server.domain.minichallenge.service;

import Hampouch.server.domain.minichallenge.dto.*;
import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import Hampouch.server.domain.minichallenge.entity.MiniChallengeDay;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeDayRepository;
import Hampouch.server.domain.minichallenge.repository.MiniChallengeRepository;
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
@RequiredArgsConstructor // final 필드들을 받는 생성자 자동 생성 — 스프링이 그 생성자로 의존성 주입(팀 스타일)
@Transactional(readOnly = true) // 기본 = 조회 전용, 쓰기 메서드(create·delete·check)만 개별 @Transactional로 덮어씀(#1과 동일)
public class MiniChallengeService {

    /**
     * 기간 화이트리스트 — 오늘만(1)/3/7/14/31일, 명세 §3 확정.
     * 설정(yml·DB)이 아니라 상수로 두는 게 맞다: 운영 중에 서버만 조정할 값이 아니라 명세가 확정한 도메인 규칙이고,
     * 안드가 같은 목록을 기간 탭으로 들고 있어(§2) 서버만 바꾸면 화면과 어긋난다. 설정으로 빼면 "서버만 고치면 되는
     * 척"하게 만들어 오히려 위험하다 — 목록이 바뀌면 서버·안드가 각자 PR을 내는 게 정상이다.
     * 규칙의 단일 출처라 다른 곳에 같은 목록을 복사하지 말 것(엔티티 durationDays 주석이 여기를 가리킨다).
     */
    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(1, 3, 7, 14, 31);

    /**
     * 제목 길이 잠정 상한 — 명세·ERD에 최대 길이가 없어 자체 결정(PM 확인 대상, 확정되면 조정).
     * title 컬럼이 Hibernate 기본 varchar(255)로 생성되므로, 여기서 안 끊으면 255자 초과 입력이
     * INSERT의 컬럼 제약에서 터져 클라이언트 입력 오류가 500으로 나간다 — 서버 검증으로 400 컷.
     */
    private static final int MAX_TITLE_LENGTH = 255;

    private final MiniChallengeRepository miniChallengeRepository;
    private final MiniChallengeDayRepository miniChallengeDayRepository;
    // "지금"의 단일 출처(ClockConfig, Asia/Seoul) — 인자 없는 now()는 서버 OS 시간대를 타고 테스트 고정도 불가(#1과 동일 이유)
    private final Clock clock;

    /**
     * 그날 나의 미니 챌린지(§1) — 요청 date(생략 시 오늘) 기준 as-of 집계. 저장 없이 계산.
     *
     * date를 LocalDate가 아닌 String으로 받는 이유: 타입 바인딩 실패는 나연 공통 핸들러의
     * Exception 폴백에 걸려 500이 되므로, 원시값으로 받아 서버가 직접 파싱·검증해 400으로 컷 —
     * #1 캘린더가 year·month를 서비스에서 검증해 BAD_REQUEST로 끊는 것과 같은 "서버가 최종 방어선" 패턴.
     */
    public DailyMiniChallengesResponse getDaily(Long userId, String dateParam) {
        LocalDate date = parseDateOrToday(dateParam);
        // 홈 날짜 스트립의 미래(다음날) 이동은 클라에서 막지만(0711 확정), 서버는 §1에 미래 date 에러 명세가 없어
        // 그대로 as-of 계산한다(자체 결정). 미래 날짜에도 기간에 걸친 미니는 목록·totalCount에 나오고,
        // "그 미래일의 체크"만 존재할 수 없을 뿐(§5 미래 체크 400) — 과거 체크 기반 스트릭·progressDays는 그대로 계산된다.
        // 미래 조회를 400으로 막을지는 명세 공백이라 PM 질문 후보.
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

        // 응답 순서는 명세에 없어 id 오름차순(생성순)으로 고정 — 자체 결정(호출마다 순서가 흔들리지 않게)
        List<DailyMiniChallengesResponse.Item> items = minis.stream()
                .filter(m -> m.isActiveOn(date)) // 목록·집계 대상 = 그날 활성 미니만(§1)
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

    /** 미니 추가(§3) — 바디는 recommendedId/custom XOR. start = 오늘(Clock), end = start + duration - 1 스냅샷. */
    @Transactional
    public CreateMiniChallengeResponse create(Long userId, CreateMiniChallengeRequest req) {
        boolean byRecommended = req.recommendedId() != null;
        boolean byCustom = req.custom() != null;
        if (byRecommended == byCustom) { // 둘 다 보냈거나 둘 다 없음 → 형태 위반
            throw new CustomException(MiniChallengeErrorCode.MINI_INVALID_BODY);
        }

        if (byRecommended) {
            // TODO(#9·#10 머지 후 배선 — 별도 후속 커밋 필수): RecommendedMiniChallengeRepository.findById(recommendedId)로
            // 카탈로그(#10)를 조회해, 없으면 지금처럼 404, 있으면 title/durationDays를 복사해
            // MiniChallenge.create(userId, rec.getTitle(), rec.getDurationDays(), LocalDate.now(clock))로 생성 + 통합 테스트 1건.
            // 주의: #10은 카탈로그 조회 GET(§2)만 구현한다 — 이 분기를 교체하지 않으면 두 브랜치가 모두 머지돼도
            // 유효한 recommendedId로 POST하면 계속 404인 반쪽 상태가 된다("추천에서 추가"는 명세 확정 기능).
            // 지금은 카탈로그 엔티티(#10 담당)가 이 브랜치에 없어 조회 자체가 불가 — 어떤 recommendedId도
            // "카탈로그에 없음"이라 즉시 404. 명세 §3의 404(recommendedId 카탈로그에 없음)와 의미가
            // 일치한다(빈 카탈로그와 동등).
            // 잠정: §3의 409(같은 추천을 이미 진행 중 — 명세도 잠정 표기)는 미구현 — 추천/커스텀 통일로
            // origin 컬럼이 ERD에 없어 "같은 추천에서 온 미니"를 식별할 수 없음. 확정되면 식별 수단부터 재설계.
            throw new CustomException(MiniChallengeErrorCode.MINI_RECOMMENDED_NOT_FOUND);
        }

        CreateMiniChallengeRequest.Custom custom = req.custom();
        // title 필수·비공백·길이 상한(잠정), durationDays 필수 — 위반은 형태 위반(MINI_INVALID_BODY)으로 자체 결정
        // (기간 "값이 이상함"과 "필드 자체가 빠짐"을 구분: 후자는 화이트리스트 이전의 폼 문제)
        if (custom.title() == null || custom.title().isBlank()
                || custom.title().length() > MAX_TITLE_LENGTH || custom.durationDays() == null) {
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

    /** 미니 삭제(§4) — 미니 행 + 그 일별 체크 행 삭제(0630 확정: 수정 대신 삭제 후 새로 생성). */
    @Transactional
    public void delete(Long userId, Long miniChallengeId) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId);
        miniChallengeDayRepository.deleteByMiniChallenge_Id(miniChallengeId); // 자식(체크 행) 먼저 — FK 제약 위반 방지
        miniChallengeRepository.delete(mini);
    }

    /**
     * 일별 체크/해제(§5) — PUT + 목표 상태 = 멱등.
     * checked=true는 upsert(이미 있으면 행 유지 → checkedAt 보존), false는 행 삭제(없어도 그대로 200).
     */
    @Transactional
    public MiniCheckResponse check(Long userId, Long miniChallengeId, MiniCheckRequest req) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId); // 존재·소유 먼저(404/403) → 그 다음 날짜 검증(#1과 동일 순서)
        LocalDate today = LocalDate.now(clock);
        // 바디의 date도 GET의 쿼리 파라미터와 같은 방어 — 서버가 직접 파싱해 형식 오류는 400, 생략은 오늘
        LocalDate date = parseDateOrToday(req.date());

        // 미래 검사를 기간 검사보다 먼저 — 미래 날짜는 기간 밖이기도 할 수 있는데(종료일 이후),
        // 그때는 명세가 코드까지 확정한 MINI_FUTURE_CHECK(선체크 상황 없음, 0707 확정)를 우선한다.
        if (date.isAfter(today)) {
            throw new CustomException(MiniChallengeErrorCode.MINI_FUTURE_CHECK);
        }
        if (!mini.isActiveOn(date)) { // 과거는 기간 안이면 허용(0707 확정) — 기간 밖만 400
            throw new CustomException(MiniChallengeErrorCode.MINI_DATE_OUT_OF_RANGE);
        }

        if (req.checked()) {
            // 알려진 한계: exists→save 사이에 같은 (미니, 날짜)의 요청이 동시에 끼면(모바일 더블탭 수준의
            // 희귀 케이스) 한쪽 INSERT가 유니크 제약(uq_mini_challenge_day)에 걸려 500이 난다.
            // 중복 행 자체는 제약이 막아 데이터는 정합. 여기서 try-catch로 삼키는 것만으론 해결이 안 된다 —
            // 제약 위반이 나는 순간 JPA 규약상 트랜잭션이 rollback-only로 표시돼 커밋에서 다시 터지기 때문.
            // 제대로 하려면 트랜잭션 분리나 잠금이 필요해서, #1의 동일 패턴(기록 upsert)과 함께 팀 차원 개선 항목으로 남긴다.
            if (!miniChallengeDayRepository.existsByMiniChallenge_IdAndCheckDate(miniChallengeId, date)) {
                miniChallengeDayRepository.save(MiniChallengeDay.of(mini, date));
            }
            // 이미 있으면 아무것도 안 함 — 기존 행 유지로 최초 checkedAt 보존(§5)
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

    /**
     * date 원시값 파싱 — GET의 쿼리 파라미터와 PUT 체크 바디가 공용.
     * 생략(null·공백)이면 오늘(Clock), ISO(yyyy-MM-dd) 파싱 실패면 400(§1 "date 형식" · §0 요청 형식 오류).
     */
    private LocalDate parseDateOrToday(String dateParam) {
        if (dateParam == null || dateParam.isBlank()) {
            return LocalDate.now(clock);
        }
        try {
            return LocalDate.parse(dateParam);
        } catch (DateTimeParseException e) {
            throw new CustomException(CommonErrorCode.BAD_REQUEST, e); // 코드명 미지정 400이라 공통 BAD_REQUEST(#1 캘린더와 동일)
        }
    }

    /**
     * 유저 미니들의 체크 이력을 in 절 한 번에 조회해 미니 id별 날짜 집합으로 그룹핑(N+1 방지).
     * asOf는 가져올 대상이 아니라 상한선이다 — "그날까지 체크된 날들"을 달라는 뜻(쿼리의 CheckDateLessThanEqual).
     * date를 유저가 고를 수 있어(과거 조회) 선을 안 그으면 그 이후 체크가 과거 화면에 새어 든다.
     * in 절에 넣는 id가 호출 직전에 이미 읽어둔 minis에서 나오므로, row.getMiniChallenge()는 지연 로딩 프록시가
     * 아니라 1차 캐시에 있는 그 엔티티 그대로다 — 그래서 추가 쿼리가 없다.
     * 설령 프록시로 오더라도 id만 읽는 건 초기화 없이 가능해 결과는 같다(두 경로 다 쿼리 0). 다만 이건 Hibernate가
     * 보장하는 동작이지 JPA 표준 보장은 아니다 — 표준은 LAZY를 힌트로만 규정한다.
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
