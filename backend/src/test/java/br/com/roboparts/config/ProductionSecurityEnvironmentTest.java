package br.com.roboparts.config;

import br.com.roboparts.RoboPartsApplication;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProductionSecurityEnvironmentTest {
    private MockEnvironment configured() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("production", new ClassPathResource("application-prod.yml")))
            env.getPropertySources().addLast(source);
        env.withProperty("app.cors.allowed-origins", "https://roboparts.example");
        env.withProperty("app.database.auto-migrate", "false").withProperty("app.registration.require-invitation", "true")
                .withProperty("spring.datasource.username", "roboparts_app")
                .withProperty("app.security.encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        env.setActiveProfiles("prod");
        return env;
    }

    @Test void acceptsTheProductionProfileAndItsSecureDefaults() throws Exception {
        var env = configured();
        new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.timeout")).isEqualTo("30m");
    }

    @Test void leavesLocalDevelopmentUnchanged() {
        MockEnvironment env = new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "false");
        new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
    }

    @ParameterizedTest @MethodSource("unsafeSettings")
    void rejectsUnsafeOverridesWithoutPrintingTheirValues(String key, String value) throws Exception {
        var env = configured().withProperty(key, value);
        assertThatThrownBy(() -> new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("produção insegura");
    }

    static Stream<Arguments> unsafeSettings() {
        return Stream.of(
                Arguments.of("app.database.auto-migrate", "true"),
                Arguments.of("app.registration.require-invitation", "false"),
                Arguments.of("spring.datasource.username", "postgres"),
                Arguments.of("app.security.encryption-key", ""),
                Arguments.of("app.security.encryption-key", "not-a-private-key"),
                Arguments.of("app.security.production", "false"),
                Arguments.of("server.servlet.session.cookie.secure", "false"),
                Arguments.of("server.servlet.session.cookie.http-only", "false"),
                Arguments.of("server.servlet.session.cookie.same-site", "none"),
                Arguments.of("server.servlet.session.timeout", "8h"),
                Arguments.of("server.servlet.session.timeout", "0"),
                Arguments.of("springdoc.api-docs.enabled", "true"),
                Arguments.of("springdoc.swagger-ui.enabled", "true"),
                Arguments.of("debug", "true"), Arguments.of("trace", "true"),
                Arguments.of("spring.mvc.log-request-details", "true"),
                Arguments.of("spring.mail.properties.mail.debug", "true"),
                Arguments.of("logging.level.root", "DEBUG"),
                Arguments.of("logging.level.org.springframework.web.servlet", "TRACE"),
                Arguments.of("LOGGING_LEVEL_ROOT", "DEBUG"),
                Arguments.of("server.forward-headers-strategy", "framework"),
                Arguments.of("server.forward-headers-strategy", "none"),
                Arguments.of("server.tomcat.remoteip.internal-proxies", ""),
                Arguments.of("server.tomcat.remoteip.internal-proxies", ".*"),
                Arguments.of("server.tomcat.remoteip.internal-proxies", "[invalid"),
                Arguments.of("app.cors.allowed-origins", ""),
                Arguments.of("app.cors.allowed-origins", "http://localhost:5173"),
                Arguments.of("app.cors.allowed-origins", "https://*.example"),
                Arguments.of("app.cors.allowed-origins", "https://private-user:private-password@example.com"),
                Arguments.of("app.cors.allowed-origins", "https://example.com/path"),
                Arguments.of("app.cors.allowed-origins", "https://example.com?private-marker=1"),
                Arguments.of("app.cors.allowed-origins", "https://example.com,")
        );
    }

    @Test void refusesCredentialsInOriginsWithoutLeakingThem() throws Exception {
        var env = configured().withProperty("app.cors.allowed-origins", "https://private-user:private-password@example.com");
        assertThatThrownBy(() -> new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication()))
                .hasMessageNotContaining("private-user").hasMessageNotContaining("private-password");
    }

    @Test void permitsDirectTlsWhenProxyHeadersAreDisabled() throws Exception {
        var env = configured().withProperty("server.forward-headers-strategy", "none").withProperty("server.ssl.enabled", "true");
        new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication());
    }

    @Test void springBootRejectsAnUnsafeProfileBeforeCreatingTheContextOrConnectingToADatabase() {
        AtomicBoolean initialized = new AtomicBoolean();
        SpringApplication app = new SpringApplication(RoboPartsApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> initialized.set(true));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=prod", "--debug=false", "--trace=false",
                "--app.cors.allowed-origins=http://localhost:5173"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.cors.allowed-origins");
        assertThat(initialized).isFalse();
    }

    @Test void aMalformedBooleanDoesNotExposeItsRawValueInTheError() throws Exception {
        var env = configured().withProperty("debug", "private-invalid-marker");
        assertThatThrownBy(() -> new ProductionSecurityEnvironment().postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("debug").hasMessageNotContaining("private-invalid-marker");
    }
}
