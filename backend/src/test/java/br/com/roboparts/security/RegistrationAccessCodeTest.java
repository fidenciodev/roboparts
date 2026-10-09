package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.roboparts.exception.ApiRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class RegistrationAccessCodeTest {
    private static final String CODE = "Test-only-invitation-code-2026-10";

    @Test
    void acceptsTheExactConfiguredCodeAndRejectsOtherInput() {
        RegistrationAccessCode accessCode = new RegistrationAccessCode(CODE);
        assertThatCode(() -> accessCode.verify(CODE)).doesNotThrowAnyException();
        for (String supplied : new String[] {null, "", "wrong", CODE.toUpperCase(), CODE + " "}) {
            assertThatThrownBy(() -> accessCode.verify(supplied)).isInstanceOfSatisfying(ApiRequestException.class,
                    failure -> {
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(failure.getCode()).isEqualTo("invalid_access_code");
                        assertThat(failure.getMessage()).doesNotContain(CODE);
                    });
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "weak-code"})
    void missingOrWeakServerConfigurationDisablesRegistration(String configured) {
        RegistrationAccessCode accessCode = new RegistrationAccessCode(configured);
        assertThatThrownBy(() -> accessCode.verify(CODE)).isInstanceOfSatisfying(ApiRequestException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failure.getCode()).isEqualTo("registration_disabled");
        });
    }

    @Test
    void overlongServerConfigurationDisablesRegistration() {
        assertThatThrownBy(() -> new RegistrationAccessCode("a".repeat(257)).verify(CODE))
                .isInstanceOfSatisfying(ApiRequestException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("registration_disabled"));
    }
}
