package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.NicknameUpdateRequest;
import Hampouch.server.domain.user.dto.request.PasswordChangeRequest;
import Hampouch.server.domain.user.dto.response.NicknameUpdateResponse;
import Hampouch.server.domain.user.dto.response.UserMeResponse;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserOperationLock userOperationLock;
    private final PasswordEncoder passwordEncoder;

    public User getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        return user;
    }

    public UserMeResponse getMyInfo(Long userId) {
        User user = getUser(userId);
        return UserMeResponse.of(user.getNickname(), user.getProfileImageUrl(), user.getEmail());
    }

    @Transactional
    public NicknameUpdateResponse updateNickname(Long userId, NicknameUpdateRequest request) {
        // 같은 유저 row를 건드리는 다른 쓰기(예: ExpenseService.create()의 lastUpdated 갱신)와
        // 직렬화하기 위해 getUser()보다 먼저 잠근다 - 순서가 바뀌면 락 없는 getUser()가 먼저 올린
        // 1차 캐시 엔티티를 이 락이 갱신해주지 못해 무의미해진다.
        userOperationLock.lock(userId);
        User user = getUser(userId);

        String nickname = request.nickname();
        // users.nickname은 대소문자 구분 없는 collation이라 DB의 uk_user_nickname도 "ab"="Ab"로 본다.
        // equals로 비교하면 케이스만 바꾸는 변경(ab->Ab)이 자기 자신과 충돌한다고 오판해 409가 난다.
        if (!nickname.equalsIgnoreCase(user.getNickname()) && userRepository.existsByNickname(nickname)) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }

        user.updateNickname(nickname);

        // 동시에 같은 닉네임으로 변경 요청이 들어오면 saveAndFlush로 여기서 즉시 UPDATE를 실행해 그 자리에서 잡고 409로 변환
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(UserErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }

        return NicknameUpdateResponse.of(user.getNickname());
    }

    @Transactional
    public void changePassword(Long userId, PasswordChangeRequest request) {
        // login()/AuthService.resetPassword()와 같은 이유로 조회 자체를 잠금 조회로 만든다.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            throw new CustomException(UserErrorCode.USER_DELETED);
        }

        if (user.isSocialUser()) {
            throw new CustomException(UserErrorCode.USER_SOCIAL_PASSWORD_CHANGE_NOT_ALLOWED);
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new CustomException(UserErrorCode.USER_CURRENT_PASSWORD_MISMATCH);
        }

        user.resetPassword(passwordEncoder.encode(request.newPassword()));
    }
}