package br.com.roboparts.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class EmailVerificationDtos {
    private EmailVerificationDtos() { }
    public record VerificationResponse(UUID verificationId, String email, long expiresInSeconds, long retryAfterSeconds) { }
    public record VerifyRequest(@NotNull UUID verificationId, @NotBlank @Pattern(regexp="[0-9]{6}") String code) {
        @Override public String toString() { return "VerifyRequest[campos omitidos]"; }
    }
    public record ResendRequest(@NotNull UUID verificationId) { }
}
