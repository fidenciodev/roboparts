package br.com.roboparts.controller;

import br.com.roboparts.dto.ActivityResponse;
import br.com.roboparts.dto.CsrfResponse;
import br.com.roboparts.dto.LoginRequest;
import br.com.roboparts.dto.RegisterRequest;
import br.com.roboparts.dto.UserResponse;
import br.com.roboparts.entity.AuditAction;
import br.com.roboparts.security.EmployeeCredentials;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação", description = "Cadastro interno e acesso por sessão de funcionário")
public class AuthController {
    private final AuthService service;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final HttpSessionCsrfTokenRepository csrfTokens;

    public AuthController(AuthService service, AuthenticationManager authenticationManager,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions,
            HttpSessionCsrfTokenRepository csrfTokens) {
        this.service = service;
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/csrf")
    @Operation(summary = "Obter o token CSRF mascarado da sessão")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastrar funcionário usando o código privado de cadastro")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) { return service.register(request); }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sessão de funcionário")
    public UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication verified = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        SessionUser user = ((EmployeeCredentials) verified.getPrincipal()).user();
        // Persist the audit event before publishing the authenticated session.
        service.record(user.id(), AuditAction.USER_LOGGED_IN);
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(user, null, verified.getAuthorities());
        sessions.onAuthentication(authenticated, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return UserResponse.from(user);
    }

    @GetMapping("/me")
    @Operation(summary = "Consultar o funcionário da sessão atual")
    public UserResponse me(@AuthenticationPrincipal SessionUser user) { return UserResponse.from(user); }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Encerrar e invalidar a sessão atual")
    public void logout(@AuthenticationPrincipal SessionUser user, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            service.record(user.id(), AuditAction.USER_LOGGED_OUT);
        } finally {
            new CsrfLogoutHandler(csrfTokens).logout(request, response, authentication);
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
        }
    }

    @GetMapping("/activities")
    @Operation(summary = "Consultar as atividades de acesso do funcionário atual")
    public List<ActivityResponse> activities(@AuthenticationPrincipal SessionUser user,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return service.activities(user.id(), limit);
    }
}
