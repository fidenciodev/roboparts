package br.com.roboparts.controller;

import br.com.roboparts.dto.MfaDtos.CodeRequest;
import br.com.roboparts.dto.UserResponse;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.io.Serializable;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class MfaLoginController {
    public static final String PENDING="roboparts.mfa.pending";
    private final MfaService mfa;private final SessionPublisher publisher;private final Clock clock;
    public MfaLoginController(MfaService mfa,SessionPublisher publisher,Clock clock) { this.mfa=mfa;this.publisher=publisher;this.clock=clock; }
    @PostMapping("/mfa-login") public UserResponse login(@Valid @RequestBody CodeRequest body,HttpServletRequest request,HttpServletResponse response) {
        HttpSession session=request.getSession(false);
        Object value=session==null?null:session.getAttribute(PENDING);
        if(!(value instanceof Pending pending)||!pending.expires().isAfter(clock.instant()))
            throw new ApiRequestException(HttpStatus.BAD_REQUEST,"mfa_challenge_expired","Entre novamente com e-mail e senha para continuar.");
        var user=mfa.login(pending.userId(),pending.version(),body.code());
        session.removeAttribute(PENDING);publisher.publish(user,request,response);return UserResponse.from(user);
    }
    public record Pending(UUID userId,long version,Instant expires) implements Serializable { }
}
