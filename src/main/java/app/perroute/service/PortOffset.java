package app.perroute.service;

public final class PortOffset {
    public static final int VALUE = read();

    private PortOffset() {}

    private static int read() {
        int value = Integer.getInteger("perroute.portOffset", 0);
        return value < 0 || value > 20_000 ? 0 : value;
    }
}
