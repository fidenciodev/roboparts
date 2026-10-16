package br.com.roboparts.security;

import br.com.roboparts.repository.EmployeeUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

// A reset revokes every previously issued session, including a concurrent old-password login.
final class PasswordVersionFilter extends OncePerRequestFilter {
    private final EmployeeUserRepository users;
    PasswordVersionFilter(EmployeeUserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user
                && !users.findSessionStateById(user.id()).map(state -> state.getActive()
                        && state.getPasswordVersion() == user.passwordVersion()).orElse(false)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
            SecurityErrorResponse.write(response, 401, "unauthenticated", "Sessão encerrada",
                    "Seu acesso foi atualizado. Entre novamente para continuar.");
            return;
        }
        chain.doFilter(request, response);
    }
}
