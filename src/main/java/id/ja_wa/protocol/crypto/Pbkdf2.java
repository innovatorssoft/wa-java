package id.ja_wa.protocol.crypto;


import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public final class Pbkdf2 {
    private Pbkdf2() {}

    public static byte[] deriveSha256(String password, byte[] salt, int iterations, int byteLength) {
        char[] chars = new char[password.length()];
        for (int i = 0; i < password.length(); i++) chars[i] = password.charAt(i);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(chars, salt, iterations, byteLength * 8)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 failed", e);
        }
    }

    public static byte[] deriveSha256(byte[] passwordUtf8, byte[] salt, int iterations, int byteLength) {
        return deriveSha256(new String(passwordUtf8, StandardCharsets.UTF_8), salt, iterations, byteLength);
    }
}
