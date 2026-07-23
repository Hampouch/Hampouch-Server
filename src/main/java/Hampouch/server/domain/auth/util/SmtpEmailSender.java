package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.auth.entity.VerificationPurpose;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

/**
 * Gmail STMP 서버를 통해 이메일을 발송하는 EmailSender의 구현체
 *
 * - 스프링부트가 mail 관련 설정을 읽어 JavaMailSender 빈을 자동으로 만듬
 * - 그 JavaMailSender를 주입받아서 메일 내용을 채운 MimeMessage를 만들고 send()를 호출
 * - 실제 SMTP 통신은 JavaMailSender 내부에서 처리
 */

@Slf4j
@Component
public class SmtpEmailSender implements EmailSender {

    private static final String FROM_NAME = "Hampouch";

    private final JavaMailSender mailSender;
    private final String mailUsername;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String mailUsername
    ) {
        this.mailSender = mailSender;
        this.mailUsername = mailUsername;
    }

    @Override
    public void send(String email, String code, VerificationPurpose purpose) {
        try {
            //MIME 형식(HTML 본문, 첨부파일 등을 담을 수 있는 표준 이메일 형식)의 이메일 메시지 객체를 만듬
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
                <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 24px;">
                    <h2 style="color: #333;">Hampouch</h2>
                    <p>안녕하세요, Hampouch입니다.</p>
                    <p>%s를 위한 인증번호를 안내드립니다.</p>
                    <div style="font-size: 28px; font-weight: bold; letter-spacing: 4px; margin: 24px 0; padding: 16px; background: #f5f5f5; text-align: center; border-radius: 8px;">%s</div>
                    <p style="color: #888; font-size: 13px;">인증번호는 발급 후 10분간 유효합니다.</p>
                    <p style="color: #888; font-size: 13px;">본인이 요청하지 않았다면 이 메일을 무시해주세요.</p>
                </div>
                """.formatted(action, code);
    }
}