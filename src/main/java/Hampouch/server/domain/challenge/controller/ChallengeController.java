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

    /**
     * 중도 포기 — 상태 전이라 201이 아닌 200 (API명세_중도포기.md).
     * 상태 전이 = 있던 챌린지의 status 값이 IN_PROGRESS→FAIL로 바뀌는 것뿐, 새 리소스(행·URL)는 안 생긴다.
     * 201 Created는 "새 리소스가 생겼다"는 신호(생성 API가 Location 헤더와 함께 쓰는 코드, RFC 9110)라
     * 여기엔 안 맞다. POST는 생성 전용 동사가 아니라 "처리 요청"이라 결과가 생성일 때만 201 — days도 같은 이유로 200.
     */
    @PostMapping("/{id}/give-up")
    public ApiResponse<GiveUpResponse> giveUp(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.giveUp(userId, id));
    }

    @PutMapping("/{id}/focus-categories")
    public ApiResponse<FocusCategoriesResponse> updateFocusCategories(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody FocusCategoriesRequest request) {
        return ApiResponse.success(service.updateFocusCategories(userId, id, request));
    }

    @PostMapping("/{id}/days")
    public ApiResponse<DayUpsertResponse> upsertDay(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody DayUpsertRequest request) {
        return ApiResponse.success(service.upsertDay(userId, id, request));
    }
}
