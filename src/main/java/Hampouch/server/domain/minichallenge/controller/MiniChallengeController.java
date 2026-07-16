package Hampouch.server.domain.minichallenge.controller;

import Hampouch.server.domain.minichallenge.dto.*;
import Hampouch.server.domain.minichallenge.service.MiniChallengeService;
import Hampouch.server.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 미니 챌린지 REST API (명세: docs/API명세_미니챌린지.md — 정일혁 파트) — 유저 소유·본 챌린지 독립(0707).
 * 이 파일의 §N 표기는 그 문서의 섹션 번호(§1 그날 조회 · §3 추가 · §4 삭제 · §5 체크), §0만 본챌린지 명세의 공통 규약.
 *
 * TODO(로그인 연동): 유저 식별은 연동 전까지 X-User-Id 헤더 스텁(기본 1) — 연동 시 JWT sub 클레임으로 교체(#1과 동일 패턴).
 */
@RestController
@RequestMapping(MiniChallengeController.BASE_PATH)
@RequiredArgsConstructor // final 필드(service)를 받는 생성자 자동 생성 — 스프링이 그 생성자로 주입
public class MiniChallengeController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로 — 문자열 중복(매직 스트링) 제거. */
    static final String BASE_PATH = "/api/mini-challenges";

    private static final String USER_HEADER = "X-User-Id";

    private final MiniChallengeService service;

    /** §1 그날 나의 미니 챌린지. date 생략 시 오늘 — 파싱·검증은 서비스가 담당(형식 오류 400). */
    @GetMapping
    public ApiResponse<DailyMiniChallengesResponse> daily(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @RequestParam(required = false) String date) {
        return ApiResponse.success(service.getDaily(userId, date));
    }

    /** §3 미니 추가 — 201 Created + Location(#1 생성 패턴). 바디 검증(XOR 등)은 서비스가 명세 코드로 처리. */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateMiniChallengeResponse>> create(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @RequestBody CreateMiniChallengeRequest request) {
        CreateMiniChallengeResponse res = service.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.miniChallengeId()))
                .body(ApiResponse.success(res));
    }

    /** §4 미니 삭제 — 204 No Content, 바디 없음(§0 래핑 규약의 예외). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** §5 일별 체크/해제 — PUT + 목표 상태 = 멱등. */
    @PutMapping("/{id}/check")
    public ApiResponse<MiniCheckResponse> check(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody MiniCheckRequest request) {
        return ApiResponse.success(service.check(userId, id, request));
    }
}
