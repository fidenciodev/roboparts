package br.com.roboparts.security;

import br.com.roboparts.exception.ApiRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class RegistrationAccessCode {
    private final byte[] configuredDigest;

    public RegistrationAccessCode(@Value("${app.registration.access-code:}") String configuredCode) {
        configuredDigest = configuredCode == null || configuredCode.isBlank()
                || configuredCode.length() < 24 || configuredCode.length() > 256 ? null : digest(configuredCode);
    }

    public void verify(String suppliedCode) {
        if (configuredDigest == null) {
            throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "registration_disabled",
                    "O cadastro está desabilitado. Entre em contato com o responsável pelo sistema.");
        }
        if (suppliedCode == null || !MessageDigest.isEqual(configuredDigest, digest(suppliedCode))) {
            throw new ApiRequestException(HttpStatus.FORBIDDEN, "invalid_access_code", "Código de cadastro inválido.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}
