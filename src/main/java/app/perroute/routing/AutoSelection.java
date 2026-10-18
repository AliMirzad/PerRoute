package app.perroute.routing;

/** The server Auto chose for one application, kept across restarts so a working route stays in place. */
public record AutoSelection(String nodeKey, long changedAt) {
    public AutoSelection {
        nodeKey = nodeKey == null ? "" : nodeKey;
        changedAt = Math.max(0L, changedAt);
    }
}
