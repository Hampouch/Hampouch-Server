package Hampouch.server.domain.auth.dto.request;

import Hampouch.server.domain.user.dto.request.PasswordChangeRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SignupRequest와 PasswordResetRequest의 비밀번호 정책이 서로 같고,
 * 실제 정규식과 안내 문구가 일치하는지 검증한다.
 * (SignupRequest는 이전에 정규식은 영문+숫자만 검사하면서 문구는 "특수문자 포함"을
 *  요구한다고 안내하는 불일치가 있었음)
 */
class PasswordPolicyTest {

    private static final String EXPECTED_PASSWORD_MESSAGE = "비밀번호는 8자 이상이며 영문, 숫자를 포함해야 합니다.";

    static ValidatorFactory factory;
    static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 회원가입_영문숫자만_있으면_통과한다() {
        SignupRequest request = new SignupRequest("test@example.com", "password1", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void 회원가입_특수문자_포함해도_통과한다() {
        SignupRequest request = new SignupRequest("test@example.com", "password1!", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void 회원가입_숫자만_있으면_거부된다() {
        SignupRequest request = new SignupRequest("test@example.com", "12345678", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 회원가입_영문만_있으면_거부된다() {
        SignupRequest request = new SignupRequest("test@example.com", "password", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 회원가입_8자_미만이면_거부된다() {
        SignupRequest request = new SignupRequest("test@example.com", "pass1", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 회원가입_안내문구는_정확히_기대한_문구와_일치한다() {
        SignupRequest request = new SignupRequest("test@example.com", "12345678", "닉네임");
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .as("빈 문구나 다른 문구로 바뀌어도 이 테스트가 잡아내야 하므로 정확한 문구를 비교한다")
                .containsExactly(EXPECTED_PASSWORD_MESSAGE);
    }

    @Test
    void 비밀번호재설정_영문숫자만_있으면_통과한다() {
        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "password1");
        Set<ConstraintViolation<PasswordResetRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void 비밀번호재설정_숫자만_있으면_거부된다() {
        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "12345678");
        Set<ConstraintViolation<PasswordResetRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 비밀번호재설정_안내문구도_회원가입과_정확히_같은_문구를_쓴다() {
        PasswordResetRequest request = new PasswordResetRequest("test@example.com", "12345678");
        Set<ConstraintViolation<PasswordResetRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(EXPECTED_PASSWORD_MESSAGE);
    }

    @Test
    void 회원가입과_비밀번호재설정의_정규식이_동일하다() throws NoSuchFieldException {
        var signupPattern = SignupRequest.class.getDeclaredField("password")
                .getAnnotation(jakarta.validation.constraints.Pattern.class);
        var resetPattern = PasswordResetRequest.class.getDeclaredField("newPassword")
                .getAnnotation(jakarta.validation.constraints.Pattern.class);

        assertThat(signupPattern.regexp()).isEqualTo(resetPattern.regexp());
    }

    @Test
    void 비밀번호변경_영문숫자만_있으면_통과한다() {
        PasswordChangeRequest request = new PasswordChangeRequest("current1", "password1");
        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void 비밀번호변경_숫자만_있으면_거부된다() {
        PasswordChangeRequest request = new PasswordChangeRequest("current1", "12345678");
        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 비밀번호변경_안내문구도_회원가입과_정확히_같은_문구를_쓴다() {
        PasswordChangeRequest request = new PasswordChangeRequest("current1", "12345678");
        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(EXPECTED_PASSWORD_MESSAGE);
    }

    @Test
    void 회원가입과_비밀번호변경의_정규식이_동일하다() throws NoSuchFieldException {
        var signupPattern = SignupRequest.class.getDeclaredField("password")
                .getAnnotation(jakarta.validation.constraints.Pattern.class);
        var changePattern = PasswordChangeRequest.class.getDeclaredField("newPassword")
                .getAnnotation(jakarta.validation.constraints.Pattern.class);

        assertThat(signupPattern.regexp()).isEqualTo(changePattern.regexp());
    }
}