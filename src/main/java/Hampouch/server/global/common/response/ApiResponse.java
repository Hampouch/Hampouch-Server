package Hampouch.server.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * - data가 null일 경우 data 필드는 안 내려감.
 * - 아래와 같이 형식 통일
 * {
 *   "code": "SUCCESS",
 *   "message": "요청이 성공했습니다.",
 *   "data": { ... }
 * }
 * 사용 예:
 * @GetMapping("/users/{userId}")
 * public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable Long userId) {
 *     UserResponse response = userService.getUser(userId);
 *     return ResponseEntity.ok(ApiResponse.success(response));
 * }
 * 보낼 데이터 없으면: return ResponseEntity.ok(ApiResponse.success());
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "요청이 성공했습니다.", data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("SUCCESS", "요청이 성공했습니다.", null);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("SUCCESS", message, data);
    }
}