DELETE older
FROM email_verifications older
JOIN email_verifications newer
  ON newer.email = older.email
 AND newer.purpose = older.purpose
 AND (
        newer.created_at > older.created_at
        OR (
            newer.created_at = older.created_at
            AND newer.verification_id > older.verification_id
        )
     );

ALTER TABLE email_verifications
    ADD CONSTRAINT uk_email_verification_email_purpose
        UNIQUE (email, purpose);