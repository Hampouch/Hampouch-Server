package Hampouch.server.domain.auth.repository;

import Hampouch.server.domain.auth.entity.EmailVerification;
import Hampouch.server.domain.auth.entity.VerificationPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByEmailAndPurpose(
            String email,
            VerificationPurpose purpose
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select e
        from EmailVerification e
        where e.email = :email
          and e.purpose = :purpose
        """)
    Optional<EmailVerification> findByEmailAndPurposeForUpdate(
            @Param("email") String email,
            @Param("purpose") VerificationPurpose purpose
    );
}