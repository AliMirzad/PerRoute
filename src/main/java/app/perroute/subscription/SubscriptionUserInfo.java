package app.perroute.subscription;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

public record SubscriptionUserInfo(long uploadBytes, long downloadBytes, long totalBytes, long expireAtMillis) {
    public static final SubscriptionUserInfo NONE = new SubscriptionUserInfo(-1, -1, -1, -1);

    public boolean known() { return totalBytes >= 0 || expireAtMillis >= 0 || uploadBytes >= 0 || downloadBytes >= 0; }

    public long usedBytes() { return Math.max(0, uploadBytes) + Math.max(0, downloadBytes); }

    public boolean hasQuota() { return totalBytes > 0; }

    public boolean hasExpiry() { return expireAtMillis > 0; }

    public double usedShare() { return hasQuota() ? Math.min(1.0, usedBytes() / (double) totalBytes) : -1; }

    public static SubscriptionUserInfo parse(String header) {
        if (header == null || header.isBlank()) return NONE;
        long upload = -1, download = -1, total = -1, expire = -1;
        for (String part : header.split("[;,]")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) continue;
            long value = parseNumber(pair[1]);
            switch (pair[0].trim().toLowerCase(Locale.ROOT)) {
                case "upload" -> upload = value;
                case "download" -> download = value;
                case "total" -> total = value;
                case "expire" -> expire = value <= 0 ? 0 : value * 1000L;
                default -> { }
            }
        }
        return new SubscriptionUserInfo(upload, download, total, expire);
    }

    private static long parseNumber(String text) {
        try {
            String trimmed = text.trim();
            return trimmed.contains(".") || trimmed.toLowerCase(Locale.ROOT).contains("e")
                    ? (long) Double.parseDouble(trimmed) : Long.parseLong(trimmed);
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    public static String title(String header) {
        if (header == null) return "";
        String value = header.trim();
        if (value.regionMatches(true, 0, "base64:", 0, 7)) {
            try { value = new String(Base64.getDecoder().decode(value.substring(7).trim()), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) { return ""; }
        }
        value = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    public static int updateIntervalHours(String header) {
        try { int hours = Integer.parseInt(header == null ? "" : header.trim()); return hours > 0 && hours <= 24 * 30 ? hours : 0; }
        catch (NumberFormatException invalid) { return 0; }
    }
}
