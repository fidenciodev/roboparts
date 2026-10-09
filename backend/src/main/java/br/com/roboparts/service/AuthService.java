package br.com.roboparts.service;

import br.com.roboparts.dto.ActivityResponse;
import br.com.roboparts.dto.RegisterRequest;
import br.com.roboparts.dto.UserResponse;
import br.com.roboparts.entity.AuditAction;
import br.com.roboparts.entity.AuditEvent;
import br.com.roboparts.entity.EmployeeUser;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.AuditEventRepository;
import br.com.roboparts.repository.EmployeeUserRepository;
import br.com.roboparts.security.RegistrationAccessCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final EmployeeUserRepository users;
    private final AuditEventRepository events;
    private final PasswordEncoder passwords;
    private final RegistrationAccessCode accessCode;

    public AuthService(EmployeeUserRepository users, AuditEventRepository events, PasswordEncoder passwords,
            RegistrationAccessCode accessCode) {
        this.users = users;
        this.events = events;
        this.passwords = passwords;
        this.accessCode = accessCode;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        accessCode.verify(request.accessCode());
        if (users.existsByEmail(request.email())) throw duplicateEmail();
        EmployeeUser user = new EmployeeUser(UUID.randomUUID(), request.name(), request.email(),
                passwords.encode(request.password()), Instant.now());
        try {
            // Flush makes a concurrent unique-email conflict surface within this operation.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailConflict(exception)) throw duplicateEmail();
            throw exception;
        }
        events.save(new AuditEvent(UUID.randomUUID(), user.getId(), AuditAction.USER_REGISTERED, Instant.now()));
        return UserResponse.from(user);
    }

    @Transactional
    public void record(UUID userId, AuditAction action) {
        events.save(new AuditEvent(UUID.randomUUID(), userId, action, Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> activities(UUID userId, int limit) {
        return events.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
                .stream().map(ActivityResponse::from).toList();
    }

    private static ApiRequestException duplicateEmail() {
        return new ApiRequestException(HttpStatus.CONFLICT, "email_already_registered", "Este e-mail já está cadastrado.");
    }

    private static boolean isEmailConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                return constraint != null && constraint.toLowerCase(java.util.Locale.ROOT).contains("users_email");
            }
        }
        return false;
    }
}
