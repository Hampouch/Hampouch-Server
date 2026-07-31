package Hampouch.server.domain.battle.controller;

import Hampouch.server.domain.battle.dto.BattleListResponse;
import Hampouch.server.domain.battle.dto.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.CreateBattleResponse;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.service.BattleService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 햄배틀 생성/목록 조회 API. 참가·상세조회는 이후 이슈.
 */
@RestController
@RequestMapping(BattleController.BASE_PATH)
@RequiredArgsConstructor
public class BattleController {

    static final String BASE_PATH = "/api/battles";

    private final BattleService battleService;

    /** POST /api/battles — 201 + Location 헤더. */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateBattleResponse>> create(
            @LoginUserId Long userId,
            @Valid @RequestBody CreateBattleRequest request) {
        CreateBattleResponse res = battleService.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.battleId()))
                .body(ApiResponse.success(res));
    }

    /** GET /api/battles — status 미지정 시 전체 상태 조회. */
    @GetMapping
    public ApiResponse<BattleListResponse> getMyBattles(
            @LoginUserId Long userId,
            @RequestParam(required = false) BattleStatus status) {
        return ApiResponse.success(battleService.getMyBattles(userId, status));
    }
}
