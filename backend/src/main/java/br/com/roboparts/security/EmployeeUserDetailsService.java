package br.com.roboparts.security;

import br.com.roboparts.entity.EmployeeUser;
import br.com.roboparts.repository.EmployeeUserRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeUserDetailsService implements UserDetailsService {
    private final EmployeeUserRepository users;
    public EmployeeUserDetailsService(EmployeeUserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        EmployeeUser user = users.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
        return new EmployeeCredentials(new SessionUser(user.getId(), user.getName(), user.getEmail()), user.getPasswordHash());
    }
}
