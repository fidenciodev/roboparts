package br.com.roboparts.service;

import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.EmployeeUserRepository;
import br.com.roboparts.security.SessionUser;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AccessManagementPolicy {
    private final Set<String> emails; private final EmployeeUserRepository users;
    public AccessManagementPolicy(EmployeeUserRepository users, @Value("${app.security.managers:}") String configured) {
        this.users = users;
        emails = new HashSet<>();
        for (String email : configured.split(",")) if (!email.isBlank()) emails.add(email.strip().toLowerCase(Locale.ROOT));
    }
    public boolean configuredManager(String email) { return emails.contains(email.strip().toLowerCase(Locale.ROOT)); }
    public boolean manager(SessionUser actor) {
        return configuredManager(actor.email()) && users.findById(actor.id())
                .map(user -> user.isActive() && user.isEmailVerified() && user.getPasswordVersion() == actor.passwordVersion()).orElse(false);
    }
    public void requireManager(SessionUser actor) {
        if (!manager(actor)) throw new ApiRequestException(HttpStatus.FORBIDDEN, "access_denied", "Somente o responsável pode gerenciar acessos.");
    }
}
