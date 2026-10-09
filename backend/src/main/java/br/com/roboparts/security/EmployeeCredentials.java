package br.com.roboparts.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class EmployeeCredentials implements UserDetails, CredentialsContainer {
    private final SessionUser user;
    private String password;

    public EmployeeCredentials(SessionUser user, String password) {
        this.user = user;
        this.password = password;
    }
    public SessionUser user() { return user; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return user.email(); }
    @Override public void eraseCredentials() { password = null; }
}
