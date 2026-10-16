package br.com.roboparts.controller;

import br.com.roboparts.dto.ActivityResponse;
import br.com.roboparts.dto.CsrfResponse;
import br.com.roboparts.dto.LoginRequest;
import br.com.roboparts.dto.RegisterRequest;
import br.com.roboparts.dto.UserResponse;
import br.com.roboparts.dto.EmailVerificationDtos.*;
import br.com.roboparts.entity.AuditAction;
import br.com.roboparts.security.EmployeeCredentials;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.service.AuthService;
import br.com.roboparts.service.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final EmailVerificationService verification;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final HttpSessionCsrfTokenRepository csrfTokens;
    private final br.com.roboparts.security.LoginAttemptLimiter accountLimiter;
    private final br.com.roboparts.service.SecurityMonitor monitor;
    private final br.com.roboparts.service.MfaService mfa;
    private final java.time.Clock clock;

    public AuthController(AuthService service, AuthenticationManager authenticationManager,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions,
            HttpSessionCsrfTokenRepository csrfTokens, EmailVerificationService verification,
            br.com.roboparts.security.LoginAttemptLimiter accountLimiter, br.com.roboparts.service.SecurityMonitor monitor,
            br.com.roboparts.service.MfaService mfa, java.time.Clock clock) {
        this.service = service;
        this.verification = verification;
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.accountLimiter = accountLimiter; this.monitor = monitor;
        this.mfa=mfa;this.clock=clock;
    }

    @GetMapping("/csrf")
    @Operation(summary = "Obter o token CSRF mascarado da sessão")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastrar funcionário usando o código privado de cadastro")
    public VerificationResponse register(@Valid @RequestBody RegisterRequest request) { return service.register(request); }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirmar o e-mail usando o código de seis dígitos")
    public UserResponse verifyEmail(@Valid @RequestBody VerifyRequest request) {
        return verification.verify(request.verificationId(),request.code());
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Reenviar o código de confirmação do e-mail")
    public VerificationResponse resend(@Valid @RequestBody ResendRequest request) {
        return verification.resend(request.verificationId());
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sessão de funcionário")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var ticket = accountLimiter.acquire(body.email());
        if (!ticket.allowed()) {
            monitor.record("LOGIN_RATE_LIMITED", body.email());
            response.setHeader("Retry-After", Long.toString(ticket.retryAfterSeconds()));
            throw new br.com.roboparts.exception.ApiRequestException(HttpStatus.TOO_MANY_REQUESTS, "account_rate_limited", "Aguarde antes de tentar entrar novamente. A recuperação de senha continua disponível.");
        }
        Authentication verified;
        try { verified = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password())); }
        catch (org.springframework.security.authentication.BadCredentialsException | org.springframework.security.authentication.DisabledException exception) {
            monitor.record("LOGIN_FAILED", body.email()); throw exception;
        }
        accountLimiter.success(ticket);
        SessionUser user = ((EmployeeCredentials) verified.getPrincipal()).user();
        if(!verification.verified(user.id()))return ResponseEntity.accepted().body(verification.request(user.id()));
        if(mfa.enabled(user.id())) {
            SecurityContext empty=SecurityContextHolder.createEmptyContext();SecurityContextHolder.setContext(empty);contexts.saveContext(empty,request,response);
            var session=request.getSession(true);request.changeSessionId();
            session.setAttribute(MfaLoginController.PENDING,new MfaLoginController.Pending(user.id(),user.passwordVersion(),clock.instant().plusSeconds(300)));
            return ResponseEntity.accepted().body(new br.com.roboparts.dto.MfaDtos.LoginResponse(true,300));
        }
        if(request.getSession(false)!=null)request.getSession(false).removeAttribute(MfaLoginController.PENDING);
        // Persist the audit event before publishing the authenticated session.
        service.record(user.id(), AuditAction.USER_LOGGED_IN);
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(user, null, verified.getAuthorities());
        sessions.onAuthentication(authenticated, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(UserResponse.from(user));
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
