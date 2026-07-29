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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 쓰기가 필요한 메서드만 개별 @Transactional로 덮어쓴다
public class UserRestService {

    private final UserRestRepository userRestRepository;
    // 진행 중 챌린지 판단은 ChallengeService가 단일 출처라 서비스째 주입한다 — 반대 방향은 리포지토리만 쓰므로 순환은 안 생긴다.
    private final ChallengeService challengeService;
    private final Clock clock; // "지금"의 단일 출처(Asia/Seoul)

    /**
     * 휴식 시작(명세 §1) — 진입점이 결과 화면뿐이라 진행 중 챌린지가 있으면 409, 이미 휴식 중이어도 409.
     * 챌린지 검사를 hasActiveChallenge로 하는 이유: 저장 status만 보면 결과 화면을 안 열어 IN_PROGRESS로
     * 남은 만료 챌린지가 휴식 시작을 잘못 막는다.
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
        try {
            UserRest rest = userRestRepository.save(UserRest.start(userId, today, req.restDays()));
            return RestStartResponse.from(rest);
        } catch (DataIntegrityViolationException e) {
            // 위 검사를 동시에 통과한 경쟁 요청이 유니크에 걸린 경우 — 같은 409로 변환.
            // 안 막으면 활성 휴식이 2건이 되어 findActiveOn이 이후 모든 요청에서 500을 낸다.
            throw openRestConflictOr(e);
        }
    }

    /**
     * 복귀 팝업의 세 선택지 처리(명세 §2) — 복귀는 재개가 아니라 휴식 종료 기록이고, 새 챌린지 생성은 POST /challenges 몫.
     * 활성 휴식이 없으면 404지만, TOMORROW로 예약해 둔 상태는 오늘까지 휴식 중이라 다시 골라 바꿀 수 있다.
     * ⚠️ 휴식을 끝내는 경로가 여기 말고 하나 더 있다 — 챌린지 생성의 자동 종료(ChallengeService.create).
     * 복귀에 부수 규칙(알림 등)을 더할 땐 그쪽도 같이 볼 것.
     */
    @Transactional
    public RestResumeResponse resume(Long userId, RestResumeRequest req) {
        LocalDate today = LocalDate.now(clock);
        UserRest rest = userRestRepository.findActiveOn(userId, today)
                .orElseThrow(() -> new CustomException(RestErrorCode.REST_NOT_ACTIVE));
        return switch (req.when()) {
            case NOW -> {
                rest.resume(today);
                yield RestResumeResponse.resumed(rest, today);
            }
            case TOMORROW -> {
                // "내일 종료 확정"으로 해석한 잠정 구현 — 대안(결정 미루기)으로 확정되면 이 분기를 extend(1) 상당으로 바꾸면 된다.
                LocalDate tomorrow = today.plusDays(1);
                rest.resume(tomorrow);
                yield RestResumeResponse.resumed(rest, tomorrow);
            }
            case EXTEND -> {
                // 연장은 예정일에 누적 가산이라 요청당 상한(@Max 3650)과 무관하게 반복하면 MySQL DATE 상한(9999년)을 넘어 500이 난다.
                // 기준일을 엔티티(extensionBaseOn)에 물어보는 이유: 여기서 직접 더하면 실제 계산과 기준이 갈려 가드를 통과한 값이 상한을 넘는다.
                if (rest.extensionBaseOn(today).plusDays(req.extendDays()).getYear() > 9999) {
                    throw new CustomException(CommonErrorCode.BAD_REQUEST);
                }
                rest.extend(today, req.extendDays());
                try {
                    // 연장은 복귀 기록을 지워 이 행을 다시 '복귀 기록 없는 휴식'으로 만든다 — 그 사이 다른 복귀 기록 없는 휴식이 생겼다면
                    // uq_user_rest_unresumed_user에 걸린다. 더티 체킹에 맡기면 커밋 때 터져 이 catch 밖에서 500이 되므로 여기서 플러시해 당겨 잡는다.
                    userRestRepository.saveAndFlush(rest);
                } catch (DataIntegrityViolationException e) {
                    throw openRestConflictOr(e);
                }
                yield RestResumeResponse.extended(rest);
            }
        };
    }

    /**
     * 저장 실패가 "복귀 기록 없는 휴식은 유저당 1건" 위반이면 409로 바꾸고, 그 외 제약 위반은 원래 예외 그대로 돌려준다.
     * 무결성 위반이라고 다 같은 원인이 아니라서 이름으로 가른다 — 특히 userId가 앞으로 User 연관관계가 되면
     * 없는 유저를 가리킨 외래키 위반까지 "이미 휴식 중입니다"로 뭉개져 원인을 못 찾게 된다.
     * 같은지가 아니라 포함인지를 보는 이유: H2는 이름을 "PUBLIC.UQ_… INDEX PUBLIC.UQ_…_INDEX_B"처럼
     * 스키마·인덱스까지 붙여 돌려준다(실측). MySQL은 인덱스 이름만 준다.
     */
    private static RuntimeException openRestConflictOr(DataIntegrityViolationException e) {
        boolean openRestConflict = e.getCause() instanceof ConstraintViolationException cause
                && cause.getConstraintName() != null
                && cause.getConstraintName().toUpperCase(Locale.ROOT)
                        .contains(UserRest.UNRESUMED_USER_UNIQUE.toUpperCase(Locale.ROOT));
        return openRestConflict ? new CustomException(RestErrorCode.REST_ALREADY_ACTIVE) : e;
    }
}
