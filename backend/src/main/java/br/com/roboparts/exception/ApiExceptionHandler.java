package br.com.roboparts.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    public ProblemDetail requestError(ApiRequestException exception) {
        return problem(exception.getStatus(), exception.getCode(), "Não foi possível concluir a operação",
                exception.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail invalidCredentials(AuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Credenciais inválidas",
                "E-mail ou senha inválidos.");
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    public ProblemDetail authenticationUnavailable(AuthenticationServiceException exception) {
        return unavailable(exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidFields(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(),
                error.getDefaultMessage() == null ? "Revise este campo." : error.getDefaultMessage()));
        ProblemDetail problem = validationError();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidRequest(Exception exception) { return validationError(); }

    @ExceptionHandler({DataAccessException.class, FlywayException.class, IllegalStateException.class})
    public ProblemDetail unavailable(RuntimeException exception) {
        log.warn("Falha de infraestrutura: {}", exception.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "Serviço indisponível",
                "Não foi possível acessar o serviço. Tente novamente em instantes.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail unexpected(RuntimeException exception) {
        log.error("Falha inesperada: {}", exception.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Falha ao concluir a operação",
                "Não foi possível concluir a operação. Tente novamente em instantes.");
    }

    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    public ProblemDetail concurrentChange(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "concurrent_update", "Dados alterados",
                "Outra operação alterou estes dados. Atualize e tente novamente.");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail missingRoute(Exception exception) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "Não encontrado", "O recurso não foi encontrado.");
    }


    private static ProblemDetail validationError() {
        return problem(HttpStatus.BAD_REQUEST, "validation_error", "Dados inválidos",
                "Revise os campos informados e tente novamente.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
