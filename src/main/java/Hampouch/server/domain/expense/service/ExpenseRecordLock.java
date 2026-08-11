package Hampouch.server.domain.expense.service;

import Hampouch.server.domain.user.repository.UserRepository;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExpenseRecordLock {

    private final UserRepository userRepository;

    /**
     * expense와 no_spend_day는 서로 다른 테이블이라 공통으로 잠글 행이 없다.
     * 항상 존재하는 사용자 행을 잠가 같은 사용자의 지출 상태 변경을 한 줄로 세운다.
     */
    public void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));
    }
}
