package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.request.NicknameUpdateRequest;
import Hampouch.server.domain.user.dto.response.NicknameUpdateResponse;
import Hampouch.server.domain.user.dto.response.UserMeResponse;
import Hampouch.server.domain.user.service.UserService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    //마이페이지 기본 정보 조회
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserMeResponse>> getMyInfo(@LoginUserId Long userId) {
        UserMeResponse response = userService.getMyInfo(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    //닉네임 변경
    @PatchMapping("/me/nickname")
    public ResponseEntity<ApiResponse<NicknameUpdateResponse>> updateNickname(
            @LoginUserId Long userId,
            @RequestBody @Valid NicknameUpdateRequest request
    ) {
        NicknameUpdateResponse response = userService.updateNickname(userId, request);
        return ResponseEntity.ok(ApiResponse.success("닉네임이 변경되었습니다.", response));
    }
}
