package br.com.roboparts.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public final class PasswordResetDtos {
    private PasswordResetDtos() { }
    public record ForgotRequest(@NotBlank @Email @Size(max = 254) String email) {
        public ForgotRequest { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "ForgotRequest[campos omitidos]"; }
    }
    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
            @NotBlank @Size(min = 12, max = 128) String password) {
        public ResetRequest { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "ResetRequest[campos e credenciais omitidos]"; }
    }
}
