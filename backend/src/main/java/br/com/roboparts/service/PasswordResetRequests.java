package br.com.roboparts.service;

import br.com.roboparts.exception.ApiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetRequests {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequests.class);
    private final PasswordResetService resets;
    private final TaskExecutor executor;
    public PasswordResetRequests(PasswordResetService resets, @Qualifier("passwordResetExecutor") TaskExecutor executor) {
        this.resets = resets; this.executor = executor;
    }
    public void request(String email) {
        // Queue before looking up the email: the HTTP response cannot disclose account existence or SMTP timing.
        try {
            executor.execute(() -> {
                try { resets.request(email); }
                catch (RuntimeException exception) { log.warn("Não foi possível processar um envio de recuperação de senha."); }
            });
        } catch (TaskRejectedException exception) {
            throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                    "O serviço está ocupado. Tente novamente em instantes.");
        }
    }
}
