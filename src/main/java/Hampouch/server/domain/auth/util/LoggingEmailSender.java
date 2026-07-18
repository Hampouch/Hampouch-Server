package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.auth.entity.VerificationPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String email, String code, VerificationPurpose purpose) {
        log.info("[이메일 인증] to={}, purpose={}, code={}", email, purpose, code);
    }
}