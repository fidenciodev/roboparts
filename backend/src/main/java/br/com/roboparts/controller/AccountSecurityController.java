package br.com.roboparts.controller;

import br.com.roboparts.dto.MfaDtos.*;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/security")
public class AccountSecurityController {
    private final MfaService mfa;private final AccessManagementPolicy policy;private final AccessManagementService management;
    private final SessionPublisher publisher;private final SecurityMonitor monitor;
    public AccountSecurityController(MfaService mfa,AccessManagementPolicy policy,AccessManagementService management,SessionPublisher publisher,SecurityMonitor monitor) {
        this.mfa=mfa;this.policy=policy;this.management=management;this.publisher=publisher;this.monitor=monitor;
    }
    @GetMapping public Map<String,Object> status(@AuthenticationPrincipal SessionUser user) { return Map.of("manager",policy.manager(user),"mfa",mfa.status(user.id())); }
    @PostMapping("/mfa/setup") public SetupResponse setup(@AuthenticationPrincipal SessionUser user,@Valid @RequestBody PasswordRequest body) { return mfa.setup(user,body.password()); }
    @PostMapping("/mfa/confirm") public RecoveryResponse confirm(@AuthenticationPrincipal SessionUser user,@Valid @RequestBody CodeRequest body,HttpServletRequest request,HttpServletResponse response) {
        var enabled=mfa.confirm(user,body.code());publisher.publish(enabled.user(),request,response);return new RecoveryResponse(enabled.recoveryCodes());
    }
    @PostMapping("/mfa/disable") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal SessionUser user,@Valid @RequestBody DisableRequest body,HttpServletRequest request,HttpServletResponse response) {
        publisher.publish(mfa.disable(user,body.password(),body.code()),request,response);
    }
    @GetMapping("/employees") public List<AccessManagementService.Employee> employees(@AuthenticationPrincipal SessionUser user) { return management.employees(user); }
    @PostMapping("/employees/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void active(@AuthenticationPrincipal SessionUser user,@PathVariable UUID id,@Valid @RequestBody ActiveRequest body) { management.active(user,id,body.active()); }
    @GetMapping("/invitations") public List<AccessManagementService.Invitation> invitations(@AuthenticationPrincipal SessionUser user) { return management.invitations(user); }
    @PostMapping("/invitations") @ResponseStatus(HttpStatus.CREATED)
    public AccessManagementService.CreatedInvitation create(@AuthenticationPrincipal SessionUser user,@Valid @RequestBody InvitationRequest body) { return management.create(user,body.email()); }
    @PostMapping("/invitations/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal SessionUser user,@PathVariable UUID id) { management.revoke(user,id); }
    @GetMapping("/events") public Map<String,Object> events(@AuthenticationPrincipal SessionUser user) { policy.requireManager(user);return Map.of("summary",monitor.summary(),"recent",monitor.recent()); }
    public record ActiveRequest(@NotNull Boolean active) { }
    public record InvitationRequest(@NotBlank @Email @Size(max=254) String email) {
        public InvitationRequest { if(email!=null)email=email.strip().toLowerCase(Locale.ROOT); }
    }
}
