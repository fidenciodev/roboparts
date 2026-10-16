package br.com.roboparts.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 / RFC 4226: SHA-1, 30-second steps, six digits for authenticator compatibility. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() { }
    public static String newSecret() { byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes); return base32(bytes); }
    public static String code(String secret, long counter, int digits) {
        if (digits != 6 && digits != 8) throw new IllegalArgumentException("Tamanho de código inválido.");
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 15;
            int binary = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % (digits == 6 ? 1_000_000 : 100_000_000));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Autenticador indisponível."); }
    }
    public static long match(String secret, String candidate, Instant instant, long lastUsed) {
        if (candidate == null || !candidate.matches("[0-9]{6}")) return -1;
        long now = instant.getEpochSecond() / 30, match = -1;
        for (long step = now - 1; step <= now + 1; step++)
            if (step > lastUsed && MessageDigest.isEqual(code(secret, step, 6).getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.US_ASCII))) match = step;
        return match;
    }
    public static String base32(byte[] bytes) {
        StringBuilder result = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; result.append(ALPHABET.charAt((buffer >>> bits) & 31)); }
        }
        if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }
    private static byte[] decode(String value) {
        var result = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char character : value.toUpperCase(Locale.ROOT).toCharArray()) {
            int number = ALPHABET.indexOf(character); if (number < 0) throw new IllegalArgumentException("Chave de autenticador inválida.");
            buffer = (buffer << 5) | number; bits += 5;
            if (bits >= 8) { bits -= 8; result.write((buffer >>> bits) & 255); }
        }
        return result.toByteArray();
    }
}
