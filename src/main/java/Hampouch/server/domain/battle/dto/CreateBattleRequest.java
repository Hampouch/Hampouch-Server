package Hampouch.server.domain.battle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /battles 요청 — 값의 유효 범위 검증(capacity/durationDays/startDate)은 애노테이션이 아니라
 * 서비스 계층에서 한다. @Min/@Max로 막으면 GlobalExceptionHandler가 전부 VALIDATION_ERROR로
 * 뭉개서, 전용 BattleErrorCode(INVALID_CAPACITY_RANGE/INVALID_DURATION_DAYS/INVALID_START_DATE)를
 * 낼 수 없기 때문. 여기 애노테이션은 "값이 아예 없거나 길이를 넘는" 경우만 담당한다.
 *
 * 모든 애노테이션에 message를 명시하는 이유(auth 도메인과 동일한 팀 컨벤션): 생략하면
 * Hibernate Validator 기본 번들이 쓰이는데, 그 문구는 JVM 기본 로케일을 탄다. 한국어 Windows
 * 로컬은 ko_KR이라 "공백일 수 없습니다"가 나가지만, 배포 컨테이너(eclipse-temurin, LANG 미설정)는
 * en_US라 "must not be blank"가 나간다 — 즉 명세에 뭘 적어도 한쪽은 틀린 말이 된다.
 */
public record CreateBattleRequest(
        @NotBlank(message = "햄배틀 제목을 입력해주세요.")
        @Size(max = 100, message = "제목은 100자 이하로 입력해주세요.")
        String title,

        @NotNull(message = "참가 정원을 입력해주세요.")
        Integer capacity,

        @NotNull(message = "참가 기간을 선택해주세요.")
        Integer durationDays,

        @NotNull(message = "시작일을 입력해주세요.")
        LocalDate startDate,

        @NotBlank(message = "벌칙을 입력해주세요.")
        @Size(max = 100, message = "벌칙은 100자 이하로 입력해주세요.")
        String penalty
) {
}
