package Hampouch.server.domain.battle.controller;

import Hampouch.server.domain.battle.dto.BattleInvitationResponse;
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
 * 햄배틀 생성/목록 조회/참가 링크 조회/참가 API. 상세조회+랭킹은 이후 이슈.
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

    /**
     * GET /api/battles/invitations/{battleCode} — 참가 전 미리보기(로그인 필수).
     * 참가 가능 여부는 응답 필드가 아니라 에러(BATTLE_FULL 등)로 분기하므로 200이면 곧 참가 가능.
     */
    @GetMapping("/invitations/{battleCode}")
    public ApiResponse<BattleInvitationResponse> getInvitation(
            @LoginUserId Long userId,
            @PathVariable String battleCode) {
        return ApiResponse.success(battleService.getInvitation(userId, battleCode));
    }

    /**
     * POST /api/battles/invitations/{battleCode} — 참가. 반환할 데이터가 없어(참가자 등록 자체가
     * 목적) ApiResponse.success()의 no-arg 버전 사용, 위치는 Location 헤더로 충분히 전달됨.
     */
    @PostMapping("/invitations/{battleCode}")
    public ResponseEntity<ApiResponse<Void>> join(
            @LoginUserId Long userId,
            @PathVariable String battleCode) {
        Long battleId = battleService.join(userId, battleCode);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + battleId))
                .body(ApiResponse.success());
    }
}
