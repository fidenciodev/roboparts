package br.com.roboparts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
        @NotBlank(message = "Informe o e-mail.") @Email(message = "Informe um e-mail válido.") @Size(max = 254, message = "O e-mail deve ter até 254 caracteres.") String email,
        @NotBlank(message = "Informe a senha.") @Size(max = 128, message = "A senha deve ter até 128 caracteres.") String password) {
    public LoginRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() { return "LoginRequest[credenciais omitidas]"; }
}
