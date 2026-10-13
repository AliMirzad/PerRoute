package app.perroute.model;

public enum RoutingMode {
    INCLUDE_SELECTED("فقط برنامه‌های انتخاب‌شده از VPN عبور کنند"),
    EXCLUDE_SELECTED("همه به‌جز برنامه‌های انتخاب‌شده از VPN عبور کنند");

    private final String label;

    RoutingMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
