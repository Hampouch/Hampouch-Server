package Hampouch.server.domain.battle.service;

import Hampouch.server.domain.battle.repository.BattleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * battleCode 발급 — 초대 링크에 그대로 노출되는 추측 불가능한 토큰이라, SecureRandom을 활용
 */
@Component
@RequiredArgsConstructor
public class BattleCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 8; // VARCHAR(30) 여유 있게 남기고, 초대 링크에 넣기 적당한 길이로 8
    private static final int MAX_ATTEMPTS = 5; // 62^8 공간에서 5연속 충돌은 사실상 불가능 — 넘으면 버그로 간주하고 즉시 터뜨림

    private final SecureRandom random = new SecureRandom();
    private final BattleRepository battleRepository;

    public String generate() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String code = randomCode();
            if (!battleRepository.existsByBattleCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("battleCode 생성 재시도 한도 초과 — 충돌률이 비정상적으로 높습니다.");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
