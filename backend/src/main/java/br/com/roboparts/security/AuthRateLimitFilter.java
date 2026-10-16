package br.com.roboparts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

final class AuthRateLimitFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    AuthRateLimitFilter(AuthRateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !("/api/auth/login".equals(path) || "/api/auth/register".equals(path)
                || "/api/auth/verify-email".equals(path) || "/api/auth/resend-verification".equals(path)
                || "/api/auth/forgot-password".equals(path) || "/api/auth/reset-password".equals(path)
                || "/api/auth/mfa-login".equals(path) || path.startsWith("/api/security/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthRateLimiter.Decision decision = limiter.accept(request.getRemoteAddr());
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            SecurityErrorResponse.write(response, 429, "rate_limited", "Muitas tentativas",
                    "Aguarde antes de tentar novamente.");
            return;
        }
        chain.doFilter(request, response);
    }
}
