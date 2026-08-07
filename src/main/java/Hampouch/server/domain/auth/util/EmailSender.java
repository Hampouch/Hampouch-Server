package Hampouch.server.domain.auth.util;

import Hampouch.server.domain.auth.entity.VerificationPurpose;

public interface EmailSender {
    void send(String email, String code, VerificationPurpose purpose);
}