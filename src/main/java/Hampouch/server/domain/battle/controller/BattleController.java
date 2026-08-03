package Hampouch.server.domain.battle.controller;

import Hampouch.server.domain.battle.dto.BattleInvitationResponse;
import Hampouch.server.domain.battle.dto.BattleListResponse;
import Hampouch.server.domain.battle.dto.CreateBattleRequest;
import Hampouch.server.domain.battle.dto.CreateBattleResponse;
import Hampouch.server.domain.battle.dto.JoinBattleResponse;
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

    /**
     * POST /api/battles — 201 + Location 헤더. 성공 메시지를 덮어쓰는 이유: 저장소의 커스텀
     * 성공 메시지는 전부 쓰기 동작에만 붙는 패턴이라(auth의 회원가입/로그인, expense의 삭제,
     * 아래 참가) 생성도 여기에 맞춘다. 조회 계열은 기본 문구 유지.
     */
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
     * POST /api/battles/invitations/{battleCode} — 참가. Location 헤더와 body 양쪽에 battleId를 싣는다
     */
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
