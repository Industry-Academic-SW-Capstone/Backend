package grit.stockIt.global.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kis_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KisCredential {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", length = 20)
    private KisCredentialType type;

    @Column(name = "credential_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private KisCredential(KisCredentialType type, String value, LocalDateTime expiresAt) {
        this.type = type;
        this.value = value;
        this.expiresAt = expiresAt;
    }

    public static KisCredential issued(KisCredentialType type, String value, LocalDateTime expiresAt) {
        return new KisCredential(type, value, expiresAt);
    }

    public boolean isValidAt(LocalDateTime now) {
        return expiresAt.isAfter(now);
    }
}
