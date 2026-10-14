package app.perroute.service;

/**
 * Shifts every loopback port PerRoute uses by a fixed amount. It is zero in normal use; integration tests set
 * the system property {@code perroute.portOffset} so a real core can run next to an installed PerRoute that
 * already holds the standard ports.
 */
public final class PortOffset {
    public static final int VALUE = read();

    private PortOffset() {}

    private static int read() {
        int value = Integer.getInteger("perroute.portOffset", 0);
        return value < 0 || value > 20_000 ? 0 : value;
    }
}
