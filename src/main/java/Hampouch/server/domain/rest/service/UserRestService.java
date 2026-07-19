package Hampouch.server.domain.rest.service;

import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.domain.rest.dto.RestResumeRequest;
import Hampouch.server.domain.rest.dto.RestResumeResponse;
import Hampouch.server.domain.rest.dto.RestStartRequest;
import Hampouch.server.domain.rest.dto.RestStartResponse;
import Hampouch.server.domain.rest.entity.UserRest;
import Hampouch.server.domain.rest.repository.UserRestRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.ChallengeErrorCode;
import Hampouch.server.global.common.exception.domain.CommonErrorCode;
import Hampouch.server.global.common.exception.domain.RestErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본 = 조회 전용, 쓰기 메서드만 개별 @Transactional로 덮어씀 — ChallengeService와 동일한 팀 스타일
public class UserRestService {

    private final UserRestRepository userRestRepository;
    // 챌린지 쪽 규칙(진행 중 존재 여부 + 만료분 lazy 확정)은 ChallengeService가 단일 출처라 서비스째 주입.
    // 반대 방향 연동(챌린지 생성 시 휴식 자동 종료)은 ChallengeService가 UserRest"Repository"만 쓰므로
    // 서비스끼리 서로 주입하는 순환(스프링 기동 실패)은 안 생긴다.
    private final ChallengeService challengeService;
    private final Clock clock; // "지금"의 단일 출처(Asia/Seoul, ClockConfig) — ChallengeService와 같은 이유

    /**
     * 휴식 시작(명세 §1) — user_rest 행 생성. 진입점이 결과 화면뿐이라(화면 확정 2026-07-07)
     * 진행 중 챌린지가 있으면 409, 이미 휴식 중이어도 409.
     *
     * 챌린지 검사를 existsInProgress 직접 조회가 아니라 hasActiveChallenge로 하는 이유:
     * 저장된 status만 보면 "기간은 끝났는데 결과 화면을 안 열어 IN_PROGRESS로 남은" 챌린지(lazy 확정 전)가
     * 휴식 시작을 잘못 막는다 — 포기(giveUp)가 409 검사 전에 만료분을 확정하는 것과 같은 원리로,
     * 확정까지 포함한 판단을 ChallengeService에 묻는다.
     */
    @Transactional
    public RestStartResponse start(Long userId, RestStartRequest req) {
        if (challengeService.hasActiveChallenge(userId)) {
            throw new CustomException(ChallengeErrorCode.CHALLENGE_ALREADY_IN_PROGRESS);
        }
        LocalDate today = LocalDate.now(clock);
        if (userRestRepository.findActiveOn(userId, today).isPresent()) {
            throw new CustomException(RestErrorCode.REST_ALREADY_ACTIVE);
        }
        UserRest rest = userRestRepository.save(UserRest.start(userId, today, req.restDays()));
        return RestStartResponse.from(rest);
    }

    /**
     * 복귀 팝업의 세 선택지 처리(명세 §2) — 복귀는 "재개"가 아니라 휴식 종료 기록이고,
     * 새 챌린지 생성은 별도로 POST /challenges가 담당한다.
     * 활성 휴식이 없으면 404 — NOW로 이미 끝낸 뒤 재호출도 여기 걸린다(오늘 복귀 = 이제 휴식 중 아님).
     * 반면 TOMORROW로 내일 복귀를 잡아둔 상태는 오늘까지 휴식 중이라(isActiveOn 참조) 다시 골라 바꿀 수 있다.
     * 변경 저장은 @Transactional 안 더티 체킹 몫(별도 save 없음 — upsertDay와 동일).
     * 휴식 종료(UserRest.resume) 경로는 여기 말고 하나 더 있다 — 챌린지 생성의 자동 종료(ChallengeService.create).
     * 복귀에 부수 규칙(알림 등)을 더할 땐 그 경로도 함께 확인할 것.
     */
    @Transactional
    public RestResumeResponse resume(Long userId, RestResumeRequest req) {
        LocalDate today = LocalDate.now(clock);
        // orElseThrow: 상자(Optional)에 값이 있으면 꺼내 주고, 비어 있으면 람다가 만든 예외를 던짐(지연 생성 — 비었을 때만 만든다).
        // 빈 상자 = 활성 휴식 없음 → 팀 예외로 404. 기본 orElseThrow()는 자바 예외라 500이 되니 항상 람다로 팀 예외를 넘긴다.
        UserRest rest = userRestRepository.findActiveOn(userId, today)
                .orElseThrow(() -> new CustomException(RestErrorCode.REST_NOT_ACTIVE));
        // switch 식: 문(statement)과 달리 값을 돌려주고, enum의 세 상수를 다 다뤘는지 컴파일러가 검사해 준다
        // (상수가 늘면 여기가 컴파일 에러로 드러남 — if 사슬이면 조용히 빠뜨림).
        // 가지가 블록({})이면 yield가 "이 가지의 결과값" 표시 — return은 메서드를 나가는 것이라 switch 식 안에선 금지.
        // 식 하나짜리 가지(->)는 yield 없이 그 값이 곧 결과.
        return switch (req.when()) {
            case NOW -> {
                rest.resume(today);
                yield RestResumeResponse.resumed(rest, today);
            }
            case TOMORROW -> {
                // "내일 종료 확정" 해석(잠정 — 질문 15). 대안 해석(결정 미루기 = EXTEND 1일 상당)과의 실차이는
                // 셋뿐이다 — 다음날 팝업 재노출 여부 · 다음날에도 휴식 중 취급인가(current 모양·배틀 매칭 중단 유지) ·
                // 이력에 복귀일(actual)이 남는가. 미루기로 확정되면 이 분기를 rest.extend(1) 상당(예정일=내일·복귀
                // 기록 없음)으로 바꾸면 된다.
                LocalDate tomorrow = today.plusDays(1);
                rest.resume(tomorrow);
                yield RestResumeResponse.resumed(rest, tomorrow);
            }
            case EXTEND -> {
                // 연장은 기존 예정일에 누적 가산이라, 반복 연장하면 요청 한 건의 상한(@Max 3650)과 무관하게
                // 예정일이 MySQL DATE 상한(9999-12-31)을 넘는 500 경로가 남는다 — 시작(restDays)은 항상
                // "오늘 + 값"이라 안전하지만 연장만 누적된다. 캘린더의 연도 가드와 같은 원칙으로 여기서 400 컷
                // (§0: 파라미터 범위 등 서비스 검증 = BAD_REQUEST).
                if (rest.getPlannedResumeDate().plusDays(req.extendDays()).getYear() > 9999) {
                    throw new CustomException(CommonErrorCode.BAD_REQUEST);
                }
                rest.extend(req.extendDays());
                yield RestResumeResponse.extended(rest);
            }
        };
    }
}
