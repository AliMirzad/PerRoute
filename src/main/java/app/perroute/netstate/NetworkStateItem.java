package app.perroute.netstate;

public record NetworkStateItem(NetworkStateKeys.Key key, String value, NetworkStateKeys.Owner owner) {
    public String display() { return NetworkStateKeys.display(key.id(), value); }
}
