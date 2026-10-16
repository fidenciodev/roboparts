package br.com.roboparts.service;
import br.com.roboparts.security.SessionUser;
import jakarta.servlet.http.*;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
@Component
public class SessionPublisher {
    private final SessionAuthenticationStrategy sessions; private final SecurityContextRepository contexts;
    public SessionPublisher(SessionAuthenticationStrategy sessions,SecurityContextRepository contexts) { this.sessions=sessions;this.contexts=contexts; }
    public void publish(SessionUser user,HttpServletRequest request,HttpServletResponse response) {
        var auth=UsernamePasswordAuthenticationToken.authenticated(user,null,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        sessions.onAuthentication(auth,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
    }
}
