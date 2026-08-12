package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.repository.BattleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * battleCode 충돌 재시도/한도 초과 처리 검증. SecureRandom이 실제로 뽑는 값 자체는 검증 대상이
 * 아니라 existsByBattleCode 응답에 따른 재시도 분기만 목으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class BattleCodeGeneratorTest {

    @Mock
    BattleRepository battleRepository;

    @Test
    @DisplayName("충돌이 없으면 첫 시도에서 8자리 코드를 바로 반환한다")
    void generate_returnsOnFirstTryWhenNoCollision() {
        when(battleRepository.existsByBattleCode(anyString())).thenReturn(false);

        String code = new BattleCodeGenerator(battleRepository).generate();

        assertThat(code).hasSize(8);
        verify(battleRepository, times(1)).existsByBattleCode(anyString());
    }

    @Test
    @DisplayName("충돌이 나면 재시도해서 충돌하지 않는 코드가 나올 때까지 계속한다")
    void generate_retriesUntilNoCollision() {
        when(battleRepository.existsByBattleCode(anyString()))
                .thenReturn(true, true, false); // 두 번 충돌 후 세 번째에 성공

        String code = new BattleCodeGenerator(battleRepository).generate();

        assertThat(code).hasSize(8);
        verify(battleRepository, times(3)).existsByBattleCode(anyString());
    }

    @Test
    @DisplayName("MAX_ATTEMPTS(5회) 내내 충돌하면 재시도를 포기하고 IllegalStateException을 던진다")
    void generate_throwsWhenAttemptsExhausted() {
        when(battleRepository.existsByBattleCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> new BattleCodeGenerator(battleRepository).generate())
                .isInstanceOf(IllegalStateException.class);
        verify(battleRepository, times(5)).existsByBattleCode(anyString());
    }
}
