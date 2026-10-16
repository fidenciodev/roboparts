package br.com.roboparts.controller;

import br.com.roboparts.dto.PasswordResetDtos.*;
import br.com.roboparts.service.PasswordResetRequests;
import br.com.roboparts.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação")
public class PasswordResetController {
    private final PasswordResetRequests requests;
    private final PasswordResetService resets;
    public PasswordResetController(PasswordResetRequests requests, PasswordResetService resets) {
        this.requests = requests; this.resets = resets;
    }
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar código de recuperação sem revelar se o e-mail está cadastrado")
    public java.util.Map<String, String> forgot(@Valid @RequestBody ForgotRequest body) {
        requests.request(body.email());
        return java.util.Map.of("message", "Se o e-mail estiver cadastrado, enviaremos um código para criar uma nova senha.");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Criar nova senha com código de uso único; encerra sessões anteriores")
    public void reset(@Valid @RequestBody ResetRequest body) { resets.reset(body.email(), body.code(), body.password()); }
}
