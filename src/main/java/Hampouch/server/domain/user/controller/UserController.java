package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.service.UserService;
import Hampouch.server.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;


    //TODO: jwt 구현 후 @AuthenticationPrincipal 사용해서 수정 필요
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMe(
            @RequestParam Long userId
    ) {
        userService.deleteMe(userId);
        return ResponseEntity.ok(ApiResponse.success("회원 탈퇴가 완료되었습니다.", null));
    }
}
