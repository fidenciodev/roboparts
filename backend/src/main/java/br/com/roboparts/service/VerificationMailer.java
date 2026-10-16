package br.com.roboparts.service;

public interface VerificationMailer {
    void sendCode(String email, String code);
    void sendPasswordResetCode(String email, String code);
    void sendPasswordChanged(String email);
}
