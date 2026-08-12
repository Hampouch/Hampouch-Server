package Hampouch.server.domain.community.controller;

import Hampouch.server.domain.community.dto.request.PresignRequest;
import Hampouch.server.domain.community.dto.response.PresignResponse;
import Hampouch.server.domain.community.service.ImagePresignService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/images")
@RequiredArgsConstructor
public class ImagePresignController {

    private final ImagePresignService imagePresignService;

    // 커뮤니티용 이미지 presigned url 일괄 발급
    @PostMapping("/presign")
    public ResponseEntity<ApiResponse<PresignResponse>> presign(
            @LoginUserId Long userId,
            @RequestBody @Valid PresignRequest request
    ) {
        PresignResponse response = imagePresignService.presign(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}