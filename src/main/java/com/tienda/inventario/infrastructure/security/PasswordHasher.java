package com.tienda.inventario.infrastructure.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256 with per-user random salt; no default credentials. */
public final class PasswordHasher {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(key);
    }

    public static boolean verify(char[] candidate, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            int iters = Integer.parseInt(parts[1]);
            if (iters < 100_000 || iters > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != KEY_BITS / 8) return false;
            return MessageDigest.isEqual(expected, derive(candidate, salt, iters));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo derivar la contraseña", e);
        } finally {
            spec.clearPassword();
        }
    }
}
