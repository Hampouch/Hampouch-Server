package Hampouch.server.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailSendRequest(

        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 최대 100자까지 입력 가능합니다.")
        String email,

        @NotBlank(message = "purpose는 SIGNUP 또는 PASSWORD_RESET만 허용됩니다.")
        @Pattern(regexp = "SIGNUP|PASSWORD_RESET", message = "purpose는 SIGNUP 또는 PASSWORD_RESET만 허용됩니다.")
        String purpose
) {
}