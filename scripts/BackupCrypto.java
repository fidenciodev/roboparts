import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Standalone Java 21 utility: encrypted backups, authenticated before restoration. Password arrives on stdin only. */
public final class BackupCrypto {
    private static final byte[] MAGIC = "ROBOPARTS-BACKUP-1\n".getBytes(StandardCharsets.US_ASCII);
    public static void main(String[] args) {
        try {
            if (args.length != 3 || !Set.of("encrypt", "decrypt").contains(args[0])) throw new IllegalArgumentException();
            String encoded = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII)).readLine();
            String secret = encoded == null ? null : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (secret == null || secret.length() < 24 || secret.length() > 256) throw new IllegalArgumentException();
            crypt(args[0].equals("encrypt"), Path.of(args[1]), Path.of(args[2]), secret.toCharArray());
            System.out.println("Arquivo verificado e processado.");
        } catch (Exception exception) { System.err.println("Não foi possível processar o backup. Confira a frase e a integridade do arquivo; detalhes omitidos."); System.exit(1); }
    }
    public static void crypt(boolean encrypt, Path source, Path destination, char[] password) throws Exception {
        Path input = source.toAbsolutePath().normalize(), output = destination.toAbsolutePath().normalize();
        long maximum = 256L * 1024 * 1024 + (encrypt ? 0 : MAGIC.length + 16 + 12 + 16);
        if (Files.size(input) > maximum) throw new IOException("Use uma solução de backup para arquivos acima de 256 MB.");
        if (input.equals(output) || Files.exists(output)) throw new IOException("Destino já existe.");
        Path temporary = output.resolveSibling(output.getFileName() + "." + UUID.randomUUID() + ".part");
        byte[] salt = new byte[16], nonce = new byte[12];
        try (InputStream in = Files.newInputStream(input); OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
            if (encrypt) {
                SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(nonce);
            } else {
                if (!MessageDigest.isEqual(in.readNBytes(MAGIC.length), MAGIC)) throw new GeneralSecurityException();
                salt = in.readNBytes(16); nonce = in.readNBytes(12);
                if (salt.length != 16 || nonce.length != 12) throw new GeneralSecurityException();
            }
            PBEKeySpec spec = new PBEKeySpec(password, salt, 600_000, 256);
            byte[] key;
            try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
            finally { spec.clearPassword(); Arrays.fill(password, '\0'); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            Arrays.fill(key, (byte) 0); cipher.updateAAD(MAGIC);
            if (encrypt) { out.write(MAGIC); out.write(salt); out.write(nonce); }
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = in.read(buffer)) != -1) { byte[] chunk = cipher.update(buffer, 0, count); if (chunk != null) out.write(chunk); }
            out.write(cipher.doFinal()); // No destination is published until the authentication tag has been checked.
        } catch (Exception exception) { Files.deleteIfExists(temporary); throw exception; }
        try {
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, output); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
