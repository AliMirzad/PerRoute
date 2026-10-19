package app.perroute.routing;

public enum AppRouteMode {
    MAIN("Main"),
    MANUAL("Manual"),
    AUTO("Auto");

    private final String label;

    AppRouteMode(String label) { this.label = label; }

    public String label() { return label; }
}
