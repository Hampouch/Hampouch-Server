package Hampouch.server.domain.challenge.controller;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 본 챌린지 REST API (정일혁 파트).
 *
 * TODO(로그인 연동): 유저 식별은 연동 전까지 X-User-Id 헤더 스텁(기본 1) — 연동 시 JWT sub 클레임으로 교체.
 */
@RestController
@RequestMapping(ChallengeController.BASE_PATH)
@RequiredArgsConstructor // final 필드(service)를 받는 생성자 자동 생성 — 스프링이 그 생성자로 주입 (static final은 제외)
public class ChallengeController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로 — 문자열 중복(매직 스트링) 제거. */
    static final String BASE_PATH = "/api/challenges";

    private static final String USER_HEADER = "X-User-Id";

    private final ChallengeService service;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateChallengeResponse>> create(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @Valid @RequestBody CreateChallengeRequest request) {
        CreateChallengeResponse res = service.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.challengeId()))
                .body(ApiResponse.success(res));
    }

    @GetMapping("/current")
    public ApiResponse<CurrentChallengeResponse> current(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId) {
        return ApiResponse.success(service.getCurrent(userId));
    }

    /** 지난 챌린지 리스트(#4, 마이페이지). /history는 리터럴 경로라 /{id}/... 패턴들과 충돌하지 않는다. */
    @GetMapping("/history")
    public ApiResponse<ChallengeHistoryResponse> history(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId) {
        return ApiResponse.success(service.getHistory(userId));
    }

    @GetMapping("/{id}/calendar")
    public ApiResponse<CalendarResponse> calendar(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @RequestParam int year,
            @RequestParam int month) {
        return ApiResponse.success(service.getCalendar(userId, id, year, month));
    }

    @GetMapping("/{id}/result")
    public ApiResponse<ResultResponse> result(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.getResult(userId, id));
    }

    /** 중도 포기 — 상태 전이(리소스 생성 아님)라 201이 아닌 200 (API명세_중도포기.md). */
    @PostMapping("/{id}/give-up")
    public ApiResponse<GiveUpResponse> giveUp(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.giveUp(userId, id));
    }

    @PostMapping("/{id}/days")
    public ApiResponse<DayUpsertResponse> upsertDay(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody DayUpsertRequest request) {
        return ApiResponse.success(service.upsertDay(userId, id, request));
    }
}
