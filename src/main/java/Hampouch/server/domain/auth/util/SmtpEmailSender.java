package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private static final String FROM_NAME = "Hampouch";

    private final JavaMailSender mailSender;
    @Value("${spring.mail.username}")
    private String mailUsername;

    @Override
    public void send(String email, String code, VerificationPurpose purpose) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            helper.setTo(email);
            helper.setFrom(mailUsername, FROM_NAME);
            helper.setSubject(subjectFor(purpose));
            helper.setText(bodyFor(code, purpose), true);

            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("이메일 발송 실패: to={}, purpose={}", email, purpose, e);
            throw new CustomException(AuthErrorCode.AUTH_EMAIL_SEND_FAILED);
        }
    }

    private String subjectFor(VerificationPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> "[Hampouch] 회원가입 이메일 인증번호";
            case PASSWORD_RESET -> "[Hampouch] 비밀번호 재설정 인증번호";
        };
    }

    private String bodyFor(String code, VerificationPurpose purpose) {
        String action = purpose == VerificationPurpose.SIGNUP ? "회원가입" : "비밀번호 재설정";
        return """
                <div style="font-family: sans-serif; padding: 24px;">
                    <h2>Hampouch %s 인증번호</h2>
                    <p>아래 인증번호를 입력해주세요.</p>
                    <div style="font-size: 28px; font-weight: bold; letter-spacing: 4px; margin: 16px 0;">%s</div>
                    <p style="color: #888;">인증번호는 발급 후 10분간 유효합니다.</p>
                </div>
                """.formatted(action, code);
    }
}