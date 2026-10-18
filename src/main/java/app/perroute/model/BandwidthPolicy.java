package app.perroute.model;

/**
 * Download and upload limits of one application, in bits per second. Zero means unlimited. The limit is
 * the application's total across all of its connections.
 */
public record BandwidthPolicy(long downloadBitsPerSecond, long uploadBitsPerSecond) {
    public static final BandwidthPolicy UNLIMITED = new BandwidthPolicy(0L, 0L);
    /** Below this a limit would make most applications unusable, so it is not accepted. */
    public static final long MIN_BITS_PER_SECOND = 64_000L;
    public static final long MAX_BITS_PER_SECOND = 100_000_000_000L;

    public BandwidthPolicy {
        downloadBitsPerSecond = normalize(downloadBitsPerSecond);
        uploadBitsPerSecond = normalize(uploadBitsPerSecond);
    }

    private static long normalize(long value) {
        if (value <= 0) return 0L;
        return Math.max(MIN_BITS_PER_SECOND, Math.min(MAX_BITS_PER_SECOND, value));
    }

    public boolean unlimited() { return downloadBitsPerSecond == 0 && uploadBitsPerSecond == 0; }

    public long downloadBytesPerSecond() { return downloadBitsPerSecond / 8; }

    public long uploadBytesPerSecond() { return uploadBitsPerSecond / 8; }

    public String encode() { return downloadBitsPerSecond + ":" + uploadBitsPerSecond; }

    public static BandwidthPolicy decode(String text) {
        if (text == null || text.isBlank()) return UNLIMITED;
        String[] parts = text.split(":", 2);
        try {
            return new BandwidthPolicy(Long.parseLong(parts[0].trim()), parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0L);
        } catch (NumberFormatException ignored) {
            return UNLIMITED;
        }
    }
}
