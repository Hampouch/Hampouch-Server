package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.request.ProfileImageAttachRequest;
import Hampouch.server.domain.user.dto.request.ProfileImagePresignRequest;
import Hampouch.server.domain.user.dto.response.ProfileImageAttachResponse;
import Hampouch.server.domain.user.dto.response.ProfileImagePresignResponse;
import Hampouch.server.domain.user.service.ProfileImageService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ProfileImageController {

    private final ProfileImageService profileImageService;

    @PostMapping("/api/users/me/profile/presigned")
    public ResponseEntity<ApiResponse<ProfileImagePresignResponse>> presign(
            @LoginUserId Long userId,
            @Valid @RequestBody ProfileImagePresignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("업로드 URL이 발급되었습니다.", profileImageService.presign(userId, request)));
    }

    @PatchMapping("/api/users/me/profile")
    public ResponseEntity<ApiResponse<ProfileImageAttachResponse>> attach(
            @LoginUserId Long userId,
            @Valid @RequestBody ProfileImageAttachRequest request) {
        ProfileImageAttachResponse response = profileImageService.attach(userId, request.imageKey());
        return ResponseEntity.ok(ApiResponse.success("프로필 사진이 변경되었습니다.", response));
    }

    @DeleteMapping("/api/users/me/profile")
    public ResponseEntity<ApiResponse<Void>> remove(@LoginUserId Long userId) {
        profileImageService.remove(userId);
        return ResponseEntity.ok(ApiResponse.success("프로필 사진이 삭제되었습니다.", null));
    }
}
