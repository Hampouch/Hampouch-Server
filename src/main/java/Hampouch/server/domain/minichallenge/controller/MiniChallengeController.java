package Hampouch.server.domain.minichallenge.controller;

import Hampouch.server.domain.minichallenge.dto.*;
import Hampouch.server.domain.minichallenge.service.MiniChallengeService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 미니 챌린지 REST API (미니 챌린지 API 명세 — 정일혁 파트) — 유저 소유·본 챌린지 독립(0707).
 * 이 파일의 §N 표기는 그 문서의 섹션 번호(§1 그날 조회 · §3 추가 · §4 삭제 · §5 체크), §0만 본챌린지 명세의 공통 규약.
 *
 * 유저 식별은 @LoginUserId — 인증 없는 요청은 주입 단계에서 401로 끊겨 여기까지 못 오므로 userId의 널 검사가 없다.
 */
@RestController
@RequestMapping(MiniChallengeController.BASE_PATH)
@RequiredArgsConstructor // final 필드(service)를 받는 생성자 자동 생성 — 스프링이 그 생성자로 주입
public class MiniChallengeController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로 — 문자열 중복(매직 스트링) 제거. */
    static final String BASE_PATH = "/api/mini-challenges";

    private final MiniChallengeService service;

    /** §1 그날 나의 미니 챌린지. date 생략 시 오늘 — 파싱·검증은 서비스가 담당(형식 오류 400). */
    @GetMapping
    public ApiResponse<DailyMiniChallengesResponse> daily(
            @LoginUserId Long userId,
            @RequestParam(required = false) String date) {
        return ApiResponse.success(service.getDaily(userId, date));
    }

    /** §3 미니 추가 — 201 Created + Location(#1 생성 패턴). 바디 검증(XOR 등)은 서비스가 명세 코드로 처리. */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateMiniChallengeResponse>> create(
            @LoginUserId Long userId,
            @RequestBody CreateMiniChallengeRequest request) {
        CreateMiniChallengeResponse res = service.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.miniChallengeId()))
                .body(ApiResponse.success(res));
    }

    /**
     * §4 미니 삭제 — 204 No Content. 204는 표준상 바디를 싣지 않는 코드라(RFC 9110) 팀 공통 ApiResponse
     * 봉투도 못 씌운다 — 그래서 이 메서드만 반환형이 ResponseEntity<Void>(팀 래핑 규약의 유일한 예외).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @LoginUserId Long userId,
            @PathVariable Long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** §5 일별 체크/해제 — PUT + 목표 상태 = 멱등. */
    @PutMapping("/{id}/check")
    public ApiResponse<MiniCheckResponse> check(
            @LoginUserId Long userId,
            @PathVariable Long id,
            @Valid @RequestBody MiniCheckRequest request) {
        return ApiResponse.success(service.check(userId, id, request));
    }
}
