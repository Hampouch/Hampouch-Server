package Hampouch.server.domain.battle.controller;

import Hampouch.server.domain.battle.dto.request.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.response.*;
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
 * 햄배틀 생성/목록 조회/상세 조회/참가 링크 조회/참가 API.
 */
@RestController
@RequestMapping(BattleController.BASE_PATH)
@RequiredArgsConstructor
public class BattleController {

    static final String BASE_PATH = "/api/battles";

    private final BattleService battleService;

    /** POST /api/battles — 201 + Location 헤더*/
    @PostMapping
    public ResponseEntity<ApiResponse<CreateBattleResponse>> create(
            @LoginUserId Long userId,
            @Valid @RequestBody CreateBattleRequest request) {
        CreateBattleResponse res = battleService.create(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.battleId()))
                .body(ApiResponse.success("햄배틀이 생성됐습니다.", res));
    }

    /** GET /api/battles — status 미지정 시 전체 상태 조회. */
    @GetMapping
    public ApiResponse<BattleListResponse> getMyBattles(
            @LoginUserId Long userId,
            @RequestParam(required = false) BattleStatus status) {
        return ApiResponse.success(battleService.getMyBattles(userId, status));
    }

    /** GET /api/battles/{battleId} — 배틀 상세 + 참가자 랭킹. 참가자만 조회 가능*/
    @GetMapping("/{battleId}")
    public ApiResponse<BattleDetailResponse> getBattleDetail(
            @LoginUserId Long userId,
            @PathVariable Long battleId) {
        return ApiResponse.success(battleService.getBattleDetail(userId, battleId));
    }

    /**GET /api/battles/invitations/{battleCode} — 참가 링크 눌렀을 때 햄배틀 정보 출력 화면.*/
    @GetMapping("/invitations/{battleCode}")
    public ApiResponse<BattleInvitationResponse> getInvitation(
            @LoginUserId Long userId,
            @PathVariable String battleCode) {
        return ApiResponse.success(battleService.getInvitation(userId, battleCode));
    }

    /**POST /api/battles/invitations/{battleCode} — 참가. Location 헤더와 body 양쪽에 battleId를 싣는다*/
    @PostMapping("/invitations/{battleCode}")
    public ResponseEntity<ApiResponse<JoinBattleResponse>> join(
            @LoginUserId Long userId,
            @PathVariable String battleCode) {
        JoinBattleResponse res = battleService.join(userId, battleCode);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.battleId()))
                .body(ApiResponse.success("햄배틀에 참가했습니다.", res));
    }
}
