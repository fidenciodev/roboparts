package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {
    @Test
    void loginAndRegistrationShareA429LimitWithRetryAfter() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(new AuthRateLimiter(1, Duration.ofMinutes(1), 10));
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("POST", "/api/auth/login", "192.0.2.1"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/register", "192.0.2.1"), blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(blocked.getContentType()).startsWith("application/problem+json");
        assertThat(blocked.getContentAsString()).contains("\"code\":\"rate_limited\"")
                .doesNotContain("password", "192.0.2.1", "accessCode");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void spoofedForwardedHeadersCannotBypassTheSocketAddressLimit() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(new AuthRateLimiter(1, Duration.ofMinutes(1), 10));
        FilterChain chain = mock(FilterChain.class);
        var first = request("POST", "/api/auth/login", "192.0.2.5");
        first.addHeader("X-Forwarded-For", "198.51.100.1");
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        var second = request("POST", "/api/auth/login", "192.0.2.5");
        second.addHeader("X-Forwarded-For", "198.51.100.2");
        var blocked = new MockHttpServletResponse();
        filter.doFilter(second, blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void identityCsrfAndLogoutDoNotConsumeTheAuthenticationLimit() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(1, Duration.ofMinutes(1), 10);
        AuthRateLimitFilter filter = new AuthRateLimitFilter(limiter);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("GET", "/api/auth/me", "192.0.2.1"), new MockHttpServletResponse(), chain);
        filter.doFilter(request("GET", "/api/auth/csrf", "192.0.2.1"), new MockHttpServletResponse(), chain);
        filter.doFilter(request("POST", "/api/auth/logout", "192.0.2.1"), new MockHttpServletResponse(), chain);
        assertThat(limiter.trackedClientCount()).isZero();
        verify(chain, times(3)).doFilter(any(), any());
    }

    private MockHttpServletRequest request(String method, String path, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(address);
        return request;
    }
}
