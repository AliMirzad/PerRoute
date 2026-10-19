package app.perroute.routing;

public record AutoSelection(String nodeKey, long changedAt) {
    public AutoSelection {
        nodeKey = nodeKey == null ? "" : nodeKey;
        changedAt = Math.max(0L, changedAt);
    }
}
