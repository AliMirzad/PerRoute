package app.perroute.routing;

/** How one selected application chooses its server while Advanced routing is on. */
public enum AppRouteMode {
    /** Follows the main server. The default, and the only mode while Advanced routing is off. */
    MAIN("Main"),
    /** A server the user picked. Never changed automatically. */
    MANUAL("Manual"),
    /** PerRoute picks and maintains a server for this application from its quality scores. */
    AUTO("Auto");

    private final String label;

    AppRouteMode(String label) { this.label = label; }

    public String label() { return label; }
}
