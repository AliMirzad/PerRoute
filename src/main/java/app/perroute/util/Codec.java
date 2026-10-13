package app.perroute.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Codec {
    private Codec() {}

    public static String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static byte[] decodeBase64(String value) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s", "");
        int padding = (4 - cleaned.length() % 4) % 4;
        cleaned += "=".repeat(padding);
        try {
            return Base64.getUrlDecoder().decode(cleaned);
        } catch (IllegalArgumentException ignored) {
            return Base64.getDecoder().decode(cleaned);
        }
    }

    public static String decodeBase64Text(String value) {
        return new String(decodeBase64(value), StandardCharsets.UTF_8);
    }
}
