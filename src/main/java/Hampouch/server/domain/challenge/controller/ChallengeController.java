package Hampouch.server.domain.challenge.controller;

import Hampouch.server.domain.challenge.dto.*;
import Hampouch.server.domain.challenge.service.ChallengeService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;

@RestController
@RequestMapping(ChallengeController.BASE_PATH)
@RequiredArgsConstructor
public class ChallengeController {

    static final String BASE_PATH = "/api/challenges";

    private final ChallengeService service;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateChallengeResponse>> create(
            @LoginUserId Long userId,
            @Valid @RequestBody CreateChallengeRequest request) {
        CreateChallengeResponse res = service.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.challengeId()))
                .body(ApiResponse.success(res));
    }

    @GetMapping("/current")
    public ApiResponse<CurrentChallengeResponse> current(
            @LoginUserId Long userId,
            @RequestParam(required = false) LocalDate date) {
        CurrentChallengeResponse response = date == null
                ? service.getCurrent(userId)
                : service.getCurrent(userId, date);
        return ApiResponse.success(response);
    }

    @GetMapping("/history")
    public ApiResponse<ChallengeHistoryResponse> history(
            @LoginUserId Long userId) {
        return ApiResponse.success(service.getHistory(userId));
    }

    @GetMapping("/recommendation")
    public ApiResponse<RecommendationResponse> recommendation(
            @LoginUserId Long userId) {
        return ApiResponse.success(service.getRecommendation(userId));
    }

    @GetMapping("/fixed-date/draft")
    public ApiResponse<NextFixedDateChallengeResponse> nextFixedDateChallenge(
            @LoginUserId Long userId) {
        return ApiResponse.success(service.getNextFixedDateChallenge(userId));
    }

    @PostMapping("/fixed-date/start")
    public ApiResponse<CreateChallengeResponse> startFixedDate(
            @LoginUserId Long userId,
            @Valid @RequestBody StartFixedDateChallengeRequest request) {
        return ApiResponse.success(service.startFixedDate(userId, request));
    }

    @GetMapping("/{id}/calendar")
    public ApiResponse<CalendarResponse> calendar(
            @LoginUserId Long userId,
            @PathVariable Long id,
            @RequestParam int year,
            @RequestParam int month) {
        return ApiResponse.success(service.getCalendar(userId, id, year, month));
    }

    @GetMapping("/{id}/result")
    public ApiResponse<ResultResponse> result(
            @LoginUserId Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.getResult(userId, id));
    }

    @PostMapping("/{id}/give-up")
    public ApiResponse<GiveUpResponse> giveUp(
            @LoginUserId Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.giveUp(userId, id));
    }

    @PostMapping("/{id}/adjust")
    public ApiResponse<AdjustGoalResponse> adjust(
            @LoginUserId Long userId,
            @PathVariable Long id,
            @Valid @RequestBody AdjustGoalRequest request) {
        return ApiResponse.success(service.adjustGoal(userId, id, request));
    }

    /** 최종 종료 — 결과 팝업의 [챌린지 종료]. give-up과 같은 상태 전이라 200. */
    @PostMapping("/{id}/close")
    public ApiResponse<CloseResponse> close(
            @LoginUserId Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(service.close(userId, id));
    }

    @PostMapping("/{id}/days")
    public ApiResponse<DayUpsertResponse> upsertDay(
            @LoginUserId Long userId,
            @PathVariable Long id,
            @Valid @RequestBody DayUpsertRequest request) {
        return ApiResponse.success(service.upsertDay(userId, id, request));
    }
}
