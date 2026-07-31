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

    private final MiniChallengeRepository miniChallengeRepository;
    private final MiniChallengeDayRepository miniChallengeDayRepository;
    // 추천 카탈로그(#10) 조회용 — "추천에서 추가"(§3)가 여기서 title·durationDays를 복사해 온다(#19 배선)
    private final RecommendedMiniChallengeRepository recommendedMiniChallengeRepository;
    // "지금"의 단일 출처(ClockConfig, Asia/Seoul) — 인자 없는 now()는 서버 OS 시간대를 타고 테스트 고정도 불가(#1과 동일 이유)
    private final Clock clock;

    /**
     * 그날 나의 미니 챌린지(§1) — 요청 date(생략 시 오늘) 기준 as-of 집계. 저장 없이 계산.
     * 집계 = 저장된 원재료(미니 행·체크 행)를 모아 세어 만드는 응답 숫자들(checkedCount·totalCount·
     * streakDays·progressDays·itemStreak). DB 어디에도 이 숫자들은 저장돼 있지 않다 — 저장해 두면
     * 체크/해제 때마다 같이 고쳐야 해 어긋날 수 있고, 매번 다시 세면 어긋날 방법이 없다(ERD 확정).
     *
     * date를 LocalDate가 아닌 String으로 받는 이유: 타입 바인딩 실패는 나연 공통 핸들러의
     * Exception 폴백에 걸려 500이 되므로, 원시값으로 받아 서버가 직접 파싱·검증해 400으로 컷 —
     * #1 캘린더가 year·month를 서비스에서 검증해 BAD_REQUEST로 끊는 것과 같은 "서버가 최종 방어선" 패턴.
     */
    public DailyMiniChallengesResponse getDaily(Long userId, String dateParam) {
        LocalDate date = parseDateOrToday(dateParam);
        // 미래 date는 400 차단(0716 자체 확정 — 초기 잠정은 허용이었으나 확정 둘의 준용으로 전환):
        // ① 홈 날짜 스트립은 다음날(미래) 이동 불가(0711 PM 확정 — 미니 홈도 같은 스트립 컴포넌트),
        // ② 미니의 미래 체크 400(0707 일혁 확정, "선체크 상황 없음")과 동일 논리로 미래 '조회'도 일어날 상황이 없다.
        // 열어 두면 클라 날짜 계산 버그가 그럴싸한 빈 응답으로 조용히 넘어가는데, 400이면 바로 드러난다.
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
            // 추천에서 추가(#19 배선) — 카탈로그(recommended_mini_challenge, #10)에서 조회해 없으면 404(§3),
            // 있으면 title·durationDays만 복사해 유저 소유 행을 새로 만든다. 참조(FK)가 아니라 값 복사인 이유(§2):
            // 카탈로그는 기획이 관리하는 공용 목록이라, 문구 수정·항목 삭제가 이미 시작한 유저 미니를 흔들면 안 된다.
            // 커스텀 경로의 값 검증(비공백·길이·화이트리스트)을 여기엔 안 거는 이유 — 카탈로그 값은 유저 입력이
            // 아니라 서버가 시드로 관리하는 데이터라, 이상하면 그건 400이 아니라 시드 수정 사안이다.
            // 잠정: §3의 409(같은 추천을 이미 진행 중 — 명세도 잠정 표기)는 미구현 — 추천/커스텀 통일로
            // origin 컬럼이 ERD에 없어 "같은 추천에서 온 미니"를 식별할 수 없음. 확정되면 식별 수단부터 재설계(질문 10).
            // findById는 Optional(값이 있을 수도, 없을 수도 있는 상자)을 반환 — orElseThrow는 값이 있으면
            // 꺼내 주고, 비어 있으면(해당 id 없음) 람다가 만든 예외를 그때 생성해 던진다. null 검사 if문의 한 줄 대체.
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

    /** 미니 삭제(§4) — 미니 행 + 그 일별 체크 행 삭제(0630 확정: 수정 대신 삭제 후 새로 생성). */
    @Transactional
    public void delete(Long userId, Long miniChallengeId) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId);
        miniChallengeDayRepository.deleteByMiniChallenge_Id(miniChallengeId); // 자식(체크 행) 먼저 — FK 제약 위반 방지
        miniChallengeRepository.delete(mini);
    }

    /**
     * 일별 체크/해제(§5) — PUT + 목표 상태 = 멱등(같은 요청을 몇 번 보내도 최종 상태가 같음 — 재전송 안전).
     * "토글해라"가 아니라 "이 상태로 만들어라"를 받는 이유다(토글은 두 번 가면 원위치라 멱등이 아님).
     * checked=true는 upsert(이미 있으면 행 유지 → checkedAt 보존), false는 행 삭제(없어도 그대로 200).
     */
    @Transactional
    public MiniCheckResponse check(Long userId, Long miniChallengeId, MiniCheckRequest req) {
        MiniChallenge mini = loadOwned(userId, miniChallengeId); // 존재·소유 먼저(404/403) → 그 다음 날짜 검증(#1과 동일 순서)
        LocalDate today = LocalDate.now(clock);
        // 바디의 date도 GET의 쿼리 파라미터와 같은 방어 — 서버가 직접 파싱해 형식 오류는 400, 생략은 오늘
        LocalDate date = parseDateOrToday(req.date());

        // 미래 검사를 기간 검사보다 먼저 — 미래 날짜는 기간 밖이기도 할 수 있는데(종료일 이후),
        // 겹치면 MINI_FUTURE_CHECK 우선이 명세 §5 확정(0716)이라 검사 순서로 그 우선순위를 보장한다.
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
     * 유저 미니들의 체크 이력을 in 절 한 번에 조회해 미니 id별 날짜 집합으로 그룹핑.
     * N+1 방지 — 미니마다 낱개 조회하면 목록 1번 + 미니 N개에 N번 = 총 N+1번의 쿼리가 나가고,
     * 데이터가 늘수록 쿼리 수가 같이 는다. in 절이면 미니가 몇 개든 이 조회는 1번으로 고정.
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
