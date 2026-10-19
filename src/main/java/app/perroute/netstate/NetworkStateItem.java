package app.perroute.netstate;

/** One reading of a snapshot, ready to show: its key, its raw value and who set it. */
public record NetworkStateItem(NetworkStateKeys.Key key, String value, NetworkStateKeys.Owner owner) {
    public String display() { return NetworkStateKeys.display(key.id(), value); }
}
