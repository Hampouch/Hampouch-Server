package Hampouch.server.domain.challenge.dto;

import Hampouch.server.domain.challenge.entity.ChallengeStatus;
import Hampouch.server.domain.rest.entity.UserRest;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/challenges/current 응답 — 진행 중 챌린지 + 현황 + 홈 소비상태.
 * 휴식 중(#8)이면 같은 엔드포인트가 휴식기 홈 데이터를 준다(휴식 명세 §3) — 그래서 두 모양이 한 레코드에 산다:
 * - 챌린지 모드: challenge + progress + consumption + warningCards + expenseInputState + adjustment (rest·keptRecords 생략)
 * - 휴식 모드: challenge를 명시적 null(키를 생략하는 게 아니라 "challenge": null 로 실어 보냄)로 + rest + keptRecords (나머지 생략)
 * 필드 단위 @JsonInclude(NON_NULL)로 안 쓰는 블록을 직렬화에서 빼되, challenge에만 안 붙인 이유:
 * 휴식 모드에서 "challenge": null 을 생략하지 않고 내려보내는 게 명세의 응답 모양이라(안드가 null로 모드 판별) 항상 실어야 한다.
 * NON_NULL의 뜻: 값이 null이면 그 키 자체를 JSON에서 생략(기본값 ALWAYS는 "키": null 로 내려보냄). 클래스에 붙이면
 * 전 필드 공통, 여기처럼 record 컴포넌트에 붙이면 그 필드만 — null만 보므로 warningCards의 빈 리스트 []는 그대로 실린다.
 * 서열은 필드 > 클래스 > 전역 설정이고, 적용 범위는 어느 쪽이든 "그 클래스가 선언한 속성"까지 —
 * 중첩 레코드(ChallengeView·RestView 등) 내부 필드에는 전파되지 않는다(내부 정책은 그 타입 자신의 몫).
 * 두 모양의 생성은 아래 정적 팩토리(forChallenge/forRest)로만 — 반쪽짜리 조합이 못 생기게 통로를 고정.
 * 즉 여덟 필드가 전부 채워지는 조합은 없다 — 항상 두 묶음(챌린지 6블록 vs rest·keptRecords) 중 한쪽만 채워지는
 * 합집합 레코드다(컨트롤러 반환 타입이 하나라 두 화면의 응답을 한 타입에 담은 것 — RestResumeResponse와 같은 장치).
 * 대안인 봉인 인터페이스(sealed + 모드별 레코드 2개)도 검토했으나 안 골랐다(자체 결정): 휴식 모드가 계약상
 * "challenge": null 을 명시해야 해서 타입을 쪼개도 항상-null 필드가 어차피 남고, null 조립이 이미 팩토리
 * 두 줄에 봉인돼 있어 득이 작다 — 응답 모드가 셋 이상으로 늘면(무효 화면 등) 그때 갈아탈 후보.
 *
 * 블록 구분 기준 = 데이터의 성격(출처·변하는 주기):
 * challenge(생성 때 정해져 고정된 설정값, 엔티티 저장분) / progress(조회 시점마다 계산되는 누적 집계)
 * / consumption(오늘 하루치 상태) / warningCards(경고 신호) / expenseInputState(최근 지출 입력 상태) / adjustment(한도 조정 현황)
 * / rest(휴식 저장분) / keptRecords(직전 챌린지 결과 — 저장 없이 계산).
 */
public record CurrentChallengeResponse(
        ChallengeView challenge,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Progress progress,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Consumption consumption,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<WarningCard> warningCards,  // 홈 경고 카드 목록 — 현재 구현값이 없어 []이며 WEAK_CATEGORY_ALERT(#52)를 위해 필드 유지
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExpenseInputState expenseInputState,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Adjustment adjustment,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        RestView rest,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        KeptRecords keptRecords
) {

    /** 챌린지 모드 — 휴식 블록 없이 6개 블록을 함께 반환한다. */
    public static CurrentChallengeResponse forChallenge(ChallengeView challenge, Progress progress,
                                                        Consumption consumption, List<WarningCard> warningCards,
                                                        ExpenseInputState expenseInputState, Adjustment adjustment) {
        return new CurrentChallengeResponse(
                challenge, progress, consumption, warningCards, expenseInputState, adjustment, null, null);
    }

    /** 휴식 모드(휴식 명세 §3) — challenge는 명시적 null, 홈에 보일 건 휴식 정보와 직전 기록뿐. */
    public static CurrentChallengeResponse forRest(RestView rest, KeptRecords keptRecords) {
        return new CurrentChallengeResponse(null, null, null, null, null, null, rest, keptRecords);
    }
    /** 응답의 challenge 부분 — 엔티티 Challenge를 그대로 노출하지 않고 화면에 보일 필드만 담은 표현(내부 필드 은닉·응답 형태를 엔티티 변경과 분리). */
    public record ChallengeView(
            Long id,
            int durationDays,
            LocalDate startDate,
            LocalDate endDate,
            int budgetTotal,
            int dailyLimit,
            ChallengeStatus status
    ) {
    }

    /**
     * 저장값이 아니라 ChallengeDay 집계(조회 시 계산).
     * 지나간 미입력일 = 0원 = 성공으로 채워 계산(0714 확정 — 결과 화면과 동일 규칙).
     * 단 오늘은 아직 하루가 안 끝나 기록이 있을 때만 포함 — 안 그러면 매일 아침 오늘이 미리 성공으로 집계되고,
     * 자정마다 미기록 오늘이 초과 연속을 끊어 경고 카드가 사라지는 문제가 생김. 즉 집계 구간 = 시작일~어제(오늘 기록 시 오늘까지).
     */
    public record Progress(
            int elapsedDays,      // 오늘이 챌린지 며칠째(시작일=1, 오늘 포함). 종료 후엔 종료일 기준으로 멈춤
            int remainingDays,    // 남은 일수(오늘 제외). elapsedDays + remainingDays = 전체 기간
            int successDays,
            int overDays,
            int currentStreak,
            int savedAmountSoFar  // 시작~오늘 누적 절약액 Σmax(0, 한도-지출). 결과 화면 최종 savedAmount와 구분해 "SoFar"
    ) {
    }

    /**
     * 홈 소비상태 — 하루 사용률 2축(2026-07-07 확정). usageRate = todaySpent / dailyLimit.
     * TODO(령준 지출 연동): todaySpent 출처 교체 — 연동 전엔 시드/POST /days 입력값.
     */
    public record Consumption(
            int todaySpent,
            int todayRemaining,  // = dailyLimit - todaySpent (파생값). 화면 표시용이라 계산 규칙을 서버 단일 출처로 두고 내려줌(클라 재계산 방지). 초과 시 음수
            int dailyLimit,
            double usageRate,
            ConsumptionCharacter character,
            AlertLevel alertLevel
    ) {
    }

    /** 한도 조정 사용/최대 횟수(챌린지당 최대 2회). */
    public record Adjustment(
            int usedCount,
            int maxCount
    ) {
    }

    /** 휴식 모드의 rest 블록 — 휴식기 홈이 보여줄 저장값 두 개(휴식 명세 §3). actualResumeDate는 화면에 안 쓰여 응답에 없다. */
    public record RestView(
            LocalDate restStartDate,
            LocalDate plannedResumeDate
    ) {
        /** 엔티티→응답 매핑의 단일 출처 — 재료가 UserRest 하나로 완결되는 변환은 DTO 안으로(from 규칙). */
        public static RestView from(UserRest rest) {
            return new RestView(rest.getRestStartDate(), rest.getPlannedResumeDate());
        }
    }

    /**
     * 휴식 모드의 keptRecords 블록 — 직전 종료 챌린지의 결과(총 절약·최고 연속)를 저장 없이 계산해 보여준다
     * (휴식 명세 §3: 휴식기 홈의 "보관 중인 내 기록" 표시값 — 결과 화면과 같은 집계 규칙).
     * 화면 근거는 휴식기 홈의 "보관 중인 내 기록" 카드(와이어프레임v2_서버영향.md §2) — 시안의 +68,200원·최고 연속
     * 14일이 성공 결과 화면 숫자와 일치한다는 관찰에서 "저장 없이 직전 결과에서 계산"을 택했다(2026-07-06 재해석).
     * 단 이 일치는 목업의 종료 챌린지가 1개면 역대 누계와 구분이 안 되는 관찰이고, 시안 행 라벨("누적 절약 금액"·
     * "최고 연속 달성")은 역대 누계로도 읽혀 집계 대상은 잠정(PM 확인 대기) — 확정돼도 이 응답 모양은 그대로.
     */
    public record KeptRecords(
            int savedAmount,
            int maxStreak
    ) {
    }
}
