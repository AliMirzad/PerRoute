package app.perroute.model;

public enum RoutingMode {
    INCLUDE_SELECTED("Only the selected apps use the tunnel"),
    EXCLUDE_SELECTED("Everything except the selected apps uses the tunnel");

    private final String label;

    RoutingMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
