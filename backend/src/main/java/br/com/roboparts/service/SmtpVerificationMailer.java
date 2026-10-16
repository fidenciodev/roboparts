package br.com.roboparts.service;

import br.com.roboparts.exception.ApiRequestException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationMailer implements VerificationMailer {
    private final ObjectProvider<JavaMailSender> senders;
    private final String username, password, from;
    public SmtpVerificationMailer(ObjectProvider<JavaMailSender> senders,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${app.email.from:}") String from) {
        this.senders=senders;this.username=username;this.password=password;this.from=from;
    }
    @Override public void sendCode(String email, String code) {
        send(email, "RoboParts — Confirme seu e-mail", "Seu código de confirmação do RoboParts é: "+code+
                "\n\nEle vale por 10 minutos e só pode ser usado uma vez. Não compartilhe este código.\n"+
                "Se você não solicitou este acesso, ignore esta mensagem.");
    }
    @Override public void sendPasswordResetCode(String email, String code) {
        send(email, "RoboParts — Recuperação de senha", "Seu código para criar uma nova senha do RoboParts é: "+code+
                "\n\nEle vale por 10 minutos e só pode ser usado uma vez. Não compartilhe este código.\n"+
                "Se você não solicitou a troca, ignore esta mensagem. Sua senha permanece a mesma.");
    }
    @Override public void sendPasswordChanged(String email) {
        send(email, "RoboParts — Sua senha foi alterada",
                "A senha da sua conta RoboParts foi alterada e as sessões anteriores perderam o acesso.\n\n"+
                "Se não foi você, recupere sua conta pela opção Esqueci minha senha e avise sua equipe.");
    }
    private void send(String email, String subject, String text) {
        JavaMailSender sender=senders.getIfAvailable();
        if(sender==null||username.isBlank()||password.isBlank()||from.isBlank())
            throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE,"email_not_configured",
                    "O envio de e-mails ainda não foi configurado no servidor.");
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(email);message.setSubject(subject);message.setText(text);
        try { sender.send(message); }
        catch(RuntimeException exception) {
            // SMTP exceptions may include credentials, recipients or message contents.
            throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE,"email_delivery_unavailable",
                    "Não foi possível enviar o código. Confira a configuração de e-mail e tente novamente.");
        }
    }
}
