package br.com.roboparts.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Validate deployment before constructing a context, opening a port or migrating a database. */
public class ProductionSecurityEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("vercel") &&
                !Arrays.asList(environment.getActiveProfiles()).contains("prod")) fail("spring.profiles.active (prod,vercel)");
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;
        if (Arrays.asList(environment.getActiveProfiles()).contains("vercel")) {
            if ("preview".equalsIgnoreCase(environment.getProperty("VERCEL_ENV"))) fail("VERCEL_ENV (banco de demonstracao reservado a production)");
            // Use platform metadata, never a request Host/X-Forwarded-Host header.
            // An explicit APP_PUBLIC_URL still takes precedence through the profile.
            if (environment.getProperty("app.cors.allowed-origins", "").isBlank()
                    && "1".equals(environment.getProperty("VERCEL"))) {
                String domain=environment.getProperty("VERCEL_PROJECT_PRODUCTION_URL", "");
                if(domain.length()>253 || !domain.matches("[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]")
                        || URI.create("https://"+domain).getHost()==null) fail("VERCEL_PROJECT_PRODUCTION_URL");
                environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "vercelCanonicalOrigin", java.util.Map.of("app.cors.allowed-origins","https://"+domain)));
            }
        }
        validate(environment);
    }

    static void validate(ConfigurableEnvironment env) {
        require(env, "app.security.production", true);
        require(env, "server.servlet.session.cookie.secure", true);
        require(env, "server.servlet.session.cookie.http-only", true);
        require(env, "springdoc.api-docs.enabled", false);
        require(env, "springdoc.swagger-ui.enabled", false);
        require(env, "debug", false);
        require(env, "trace", false);
        require(env, "spring.mvc.log-request-details", false);
        require(env, "spring.mail.properties.mail.debug", false);
        String sameSite = env.getProperty("server.servlet.session.cookie.same-site", "");
        if (!sameSite.equalsIgnoreCase("lax") && !sameSite.equalsIgnoreCase("strict")) fail("server.servlet.session.cookie.same-site");
        String origins = env.getProperty("app.cors.allowed-origins", "");
        if (origins.isBlank()) fail("app.cors.allowed-origins");
        for (String origin : origins.split(",", -1)) {
            try {
                URI uri = URI.create(origin.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().contains("*")
                        || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                        || uri.getPort() == 0 || uri.getPort() > 65535) fail("app.cors.allowed-origins");
            } catch (IllegalArgumentException exception) { fail("app.cors.allowed-origins"); }
        }
        require(env, "app.database.auto-migrate", false);
        require(env, "app.registration.require-invitation", true);
        if (env.getProperty("spring.datasource.username", "postgres").equalsIgnoreCase("postgres")) fail("spring.datasource.username (usuário exclusivo da aplicação)");
        try {
            if (java.util.Base64.getDecoder().decode(env.getProperty("app.security.encryption-key", "")).length != 32) fail("app.security.encryption-key (chave persistente de 32 bytes)");
        } catch (IllegalArgumentException exception) { fail("app.security.encryption-key (chave persistente de 32 bytes)"); }
        try {
            Duration timeout = DurationStyle.detectAndParse(env.getProperty("server.servlet.session.timeout", "30m"));
            if (timeout.compareTo(Duration.ofMinutes(1)) < 0 || timeout.compareTo(Duration.ofHours(1)) > 0)
                fail("server.servlet.session.timeout (1m a 1h)");
        } catch (IllegalArgumentException exception) { fail("server.servlet.session.timeout (1m a 1h)"); }
        String forwarding = env.getProperty("server.forward-headers-strategy", "none");
        if (!forwarding.equalsIgnoreCase("none") && !forwarding.equalsIgnoreCase("native"))
            fail("server.forward-headers-strategy (native ou none)");
        if (forwarding.equalsIgnoreCase("none") && !"true".equalsIgnoreCase(env.getProperty("server.ssl.enabled")))
            fail("server.ssl.enabled (TLS direto quando forward-headers-strategy=none)");
        if (forwarding.equalsIgnoreCase("native")) {
            String proxies = env.getProperty("server.tomcat.remoteip.internal-proxies", "");
            if (proxies.isBlank()) fail("server.tomcat.remoteip.internal-proxies");
            try {
                Pattern pattern = Pattern.compile(proxies);
                if (pattern.matcher("untrusted-host.example").matches()) fail("server.tomcat.remoteip.internal-proxies");
            } catch (IllegalArgumentException exception) { fail("server.tomcat.remoteip.internal-proxies"); }
        }
        for (var source : env.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String key : enumerable.getPropertyNames()) {
                if (!key.toLowerCase(Locale.ROOT).replace('_', '.').startsWith("logging.level.")) continue;
                String level = env.getProperty(key, "");
                if (java.util.Set.of("DEBUG", "TRACE", "ALL").contains(level.toUpperCase(Locale.ROOT))) fail("logging.level (DEBUG/TRACE/ALL)");
            }
        }
    }

    private static void require(ConfigurableEnvironment env, String key, boolean expected) {
        if (!Boolean.toString(expected).equalsIgnoreCase(env.getProperty(key))) fail(key);
    }
    private static void fail(String key) {
        // Report property names only: configuration values can contain credentials or private hostnames.
        throw new IllegalStateException("Configuração de produção insegura ou incompleta: revise " + key + ".");
    }
}
