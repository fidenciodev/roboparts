package br.com.roboparts.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class MfaDtos {
    private MfaDtos() { }
    public record PasswordRequest(@NotBlank @Size(min=12,max=128) String password) {
        @Override public String toString() { return "PasswordRequest[credencial omitida]"; }
    }
    public record CodeRequest(@NotBlank @Size(max=80) String code) {
        @Override public String toString() { return "CodeRequest[código omitido]"; }
    }
    public record DisableRequest(@NotBlank @Size(min=12,max=128) String password,@NotBlank @Size(max=80) String code) {
        @Override public String toString() { return "DisableRequest[credenciais omitidas]"; }
    }
    public record SetupResponse(String secret,String uri,Instant expiresAt) {
        @Override public String toString() { return "SetupResponse[segredo omitido]"; }
    }
    public record RecoveryResponse(List<String> recoveryCodes) {
        @Override public String toString() { return "RecoveryResponse[códigos omitidos]"; }
    }
    public record StatusResponse(boolean enabled,long remainingRecoveryCodes) { }
    public record LoginResponse(boolean mfaRequired,int expiresInSeconds) { }
}
