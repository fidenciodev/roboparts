package br.com.roboparts.service;

import br.com.roboparts.exception.ApiRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class SmtpVerificationMailerTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(sender);return provider;
    }
    @Test void usesTheConfiguredSenderAndOnlyEmailsTheRequestedAddress() {
        JavaMailSender sender=mock(JavaMailSender.class);
        new SmtpVerificationMailer(provider(sender),"sender@gmail.com","test-app-password","sender@gmail.com").sendCode("employee@example.com","012345");
        ArgumentCaptor<SimpleMailMessage> message=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("sender@gmail.com");
        assertThat(message.getValue().getTo()).containsExactly("employee@example.com");
        assertThat(message.getValue().getText()).contains("012345","10 minutos").doesNotContain("test-app-password");
    }
    @Test void rejectsMissingConfigurationAndSanitizesMailExceptions() {
        JavaMailSender sender=mock(JavaMailSender.class);
        assertThatThrownBy(()->new SmtpVerificationMailer(provider(sender),"","","sender@gmail.com").sendCode("employee@example.com","012345"))
                .isInstanceOfSatisfying(ApiRequestException.class,e->assertThat(e.getCode()).isEqualTo("email_not_configured"));
        verifyNoInteractions(sender);
        doThrow(new MailSendException("SMTP test-app-password 012345")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(()->new SmtpVerificationMailer(provider(sender),"sender@gmail.com","test-app-password","sender@gmail.com").sendCode("employee@example.com","012345"))
                .isInstanceOfSatisfying(ApiRequestException.class,e->{assertThat(e.getCode()).isEqualTo("email_delivery_unavailable");assertThat(e.getMessage()).doesNotContain("test-app-password","012345");assertThat(e.getCause()).isNull();});
    }

    @Test void recoveryAndChangedPasswordEmailsUseDifferentSubjectsAndNeverIncludeAPassword() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var mailer = new SmtpVerificationMailer(provider(sender), "sender@gmail.com", "test-app-password", "sender@gmail.com");
        mailer.sendPasswordResetCode("employee@example.com", "012345");
        mailer.sendPasswordChanged("employee@example.com");
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).getSubject()).contains("Recuperação");
        assertThat(messages.getAllValues().get(0).getText()).contains("012345", "10 minutos");
        assertThat(messages.getAllValues().get(1).getSubject()).contains("alterada");
        assertThat(messages.getAllValues().get(1).getText()).doesNotContain("012345", "test-app-password");
        assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(message.getTo()).containsExactly("employee@example.com"));
    }
}
