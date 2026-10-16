package br.com.roboparts.config;

import br.com.roboparts.security.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@Profile("vercel")
@EnableJdbcHttpSession(tableName="roboparts.web_sessions",maxInactiveIntervalInSeconds=1800)
public class CloudSessionConfig {
    @Bean
    @DependsOn("entityManagerFactory")
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> cloudSessions(DataSource source,
            @Value("${server.servlet.session.timeout:30m}") String timeout) {
        CloudSchema.validate(source);
        return repository -> repository.setDefaultMaxInactiveInterval(DurationStyle.detectAndParse(timeout));
    }
    @Bean
    DefaultCookieSerializer cookieSerializer() {
        var cookie=new DefaultCookieSerializer();
        cookie.setCookieName("JSESSIONID");cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(true);cookie.setSameSite("Lax");
        cookie.setUseBase64Encoding(false);
        return cookie;
    }
    @Bean
    FilterRegistrationBean<SessionRepositoryFilter<? extends Session>> sharedSessionFilterRegistration(
            SessionRepositoryFilter<? extends Session> filter) {
        var registration=new FilterRegistrationBean<SessionRepositoryFilter<? extends Session>>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        return registration;
    }
    @Bean
    AttemptWindowStore sharedAttemptWindows(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        return new JdbcAttemptWindowStore(jdbc,manager);
    }
}
