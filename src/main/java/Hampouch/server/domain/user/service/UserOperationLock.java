package Hampouch.server.domain.user.service;

import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 동일 사용자의 챌린지·휴식 상태 변경을 직렬화하는 조정 락. */
@Component
@RequiredArgsConstructor
public class UserOperationLock {

    private final UserRepository userRepository;

    /** 호출자의 쓰기 트랜잭션이 끝날 때까지 사용자 단위 상태 변경을 직렬화한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));
    }
}
