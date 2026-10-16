package br.com.roboparts.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecuritySecrets {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public SecuritySecrets(@Value("${app.security.encryption-key:}") String configured,
            @Value("${app.security.key-file:${user.home}/.roboparts/security.key}") String keyFile) {
        try {
            if (!configured.isBlank()) key = Base64.getDecoder().decode(configured);
            else {
                Path file = Path.of(keyFile).toAbsolutePath().normalize();
                Files.createDirectories(file.getParent());
                if (!Files.exists(file)) {
                    byte[] created = new byte[32]; random.nextBytes(created);
                    try {
                        Files.write(file, created, StandardOpenOption.CREATE_NEW);
                        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                        else {
                            var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);
                            if(acl!=null)acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
                                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                                    .setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
                        }
                    } catch (FileAlreadyExistsException concurrentCreation) { /* Another instance created the same key. */ }
                }
                key = Files.readAllBytes(file);
            }
            if (key.length != 32) throw new IllegalArgumentException();
        } catch (Exception exception) { throw new IllegalStateException("Configure uma chave de segurança persistente e válida; valores omitidos."); }
    }
    public String encrypt(String value, UUID userId) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.US_ASCII));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length); System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Não foi possível proteger a configuração de segurança."); }
    }
    public String decrypt(String value, UUID userId) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 29) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.US_ASCII));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new IllegalStateException("Não foi possível ler a configuração de segurança. Confira a chave original."); }
    }
    public String fingerprint(String email) {
        try {
            Mac derivation=Mac.getInstance("HmacSHA256");derivation.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] loggingKey=derivation.doFinal("RoboParts account fingerprint v1".getBytes(StandardCharsets.US_ASCII));
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(loggingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Não foi possível registrar a atividade de segurança."); }
    }
    public static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 indisponível."); }
    }
}
