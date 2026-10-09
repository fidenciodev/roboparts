package br.com.roboparts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank(message = "Informe o nome.") @Size(min = 2, max = 100, message = "O nome deve ter de 2 a 100 caracteres.") String name,
        @NotBlank(message = "Informe o e-mail.") @Email(message = "Informe um e-mail válido.") @Size(max = 254, message = "O e-mail deve ter até 254 caracteres.") String email,
        @NotBlank(message = "Informe a senha.") @Size(min = 12, max = 128, message = "A senha deve ter de 12 a 128 caracteres.") String password,
        @NotBlank(message = "Informe o código de cadastro.") @Size(max = 256, message = "Código de cadastro inválido.") String accessCode) {
    public RegisterRequest {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() { return "RegisterRequest[campos e credenciais omitidos]"; }
}
