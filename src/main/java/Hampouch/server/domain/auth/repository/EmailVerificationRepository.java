package Hampouch.server.domain.auth.repository;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findEmailAndPurpose(
            String email,
            VerificationPurpose purpose
    );
}