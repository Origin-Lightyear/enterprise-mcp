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
    private static final String str = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArxp+TtiRRZtX3ypCVN/sBvcHmlrFpwS6AXhh0muU1Yn1xz62x0iL8JXFKKdmR9Fk4dbD5TmyfgA26tUKZw873dMrAHX8EWBAoPbMngwroWXi1Zw33O48RR39rrjigw/dxuZHuy9qxrkxdOysY4FToppjFw2Ij2mRnZ3RnzhXq13jAND3yJnwCdvoPhLfImZX9evNG61hNeTAODYyTq09kkW87UA0rLG8DbIvi52UjI+BG5UZrGdBMcCEzgm4jLI6G6YmJIIW/DPk/s+S+TUqemtw9cLofzto1pZnN54zh32KNzAZN+0PUIAXMg0qvn9xakQBb4fD0BI8eP3nBbbevwIDAQAB";
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
