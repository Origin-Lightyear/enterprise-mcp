package com.kevin.mcp.agent.connector.util;

import java.security.MessageDigest;
import java.util.Base64;

/**
 * Gpkf
 *
 * @author Kevin
 * 2026/7/30
 */
public class Gpkf {
    private static final String str = """

            """;
    public static void main(String[] args) {
        String normalized = str.replaceAll("\\s+", "").strip();
        byte[] decoded = Base64.getDecoder().decode(normalized);
        String actualFingerprint = sha256Fingerprint(decoded);
        System.out.println(actualFingerprint);
    }

    static String sha256Fingerprint(byte[] encodedKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedKey);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate key fingerprint", exception);
        }
    }
}
