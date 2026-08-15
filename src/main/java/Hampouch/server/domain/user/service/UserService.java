package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.dto.request.NicknameUpdateRequest;
import Hampouch.server.domain.user.dto.response.NicknameUpdateResponse;
import Hampouch.server.domain.user.dto.response.UserMeResponse;
import Hampouch.server.domain.user.entity.User;
import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserOperationLock userOperationLock;

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
        if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
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
}