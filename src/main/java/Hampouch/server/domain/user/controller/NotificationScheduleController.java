package Hampouch.server.domain.user.controller;

import Hampouch.server.domain.user.dto.request.NotificationScheduleUpdateRequest;
import Hampouch.server.domain.user.dto.response.NotificationScheduleResponse;
import Hampouch.server.domain.user.service.NotificationScheduleService;
import Hampouch.server.global.common.response.ApiResponse;
import Hampouch.server.global.security.LoginUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/notification/schedule")
@RequiredArgsConstructor
public class NotificationScheduleController {

    private final NotificationScheduleService notificationScheduleService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationScheduleResponse>> getSchedule(@LoginUserId Long userId) {
        NotificationScheduleResponse response = notificationScheduleService.getSchedule(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<NotificationScheduleResponse>> updateSchedule(
            @LoginUserId Long userId,
            @RequestBody @Valid NotificationScheduleUpdateRequest request
    ) {
        NotificationScheduleResponse response = notificationScheduleService.updateSchedule(userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
