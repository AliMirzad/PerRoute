package app.perroute.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import app.perroute.util.Json;

public record ProxyNode(
        String key,
        String name,
        Protocol protocol,
        String address,
        int port,
        String credential,
        Map<String, String> options,
        String sourceLink
) {
    public ProxyNode {
        Objects.requireNonNull(protocol, "protocol");
        address = Objects.requireNonNullElse(address, "").trim();
        credential = Objects.requireNonNullElse(credential, "");
        name = Objects.requireNonNullElse(name, protocol.label() + " - " + address).trim();
        key = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
        options = Map.copyOf(options == null ? Map.of() : new LinkedHashMap<>(options));
        sourceLink = Objects.requireNonNullElse(sourceLink, "");
        if (address.isBlank()) throw new IllegalArgumentException("Server address is empty");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid server port: " + port);
        if (credential.isBlank()) throw new IllegalArgumentException("Credential is empty");
    }

    public String option(String key) {
        return options.getOrDefault(key, "");
    }

    public String endpoint() {
        return address + ":" + port;
    }

    public String exportText() {
        String profileJson = option("rawProfileJson");
        return profileJson.isBlank() ? sourceLink : profileJson;
    }

    public String identity() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("protocol", protocol.name());
        if (protocol == Protocol.XRAY_PROFILE) {
            String raw = option("rawProfileJson");
            if (!raw.isBlank()) {
                try {
                    Map<String, Object> profile = new LinkedHashMap<>(Json.object(raw));
                    profile.remove("remarks");
                    profile.remove("log");
                    profile.remove("inbounds");
                    profile.remove("routing");
                    identity.put("profile", profile);
                    return Json.stringify(identity);
                } catch (RuntimeException ignored) { }
            }
        }
        identity.put("address", address.toLowerCase(java.util.Locale.ROOT));
        identity.put("port", port);
        identity.put("credential", credential);
        identity.put("options", new TreeMap<>(options));
        return Json.stringify(identity);
    }

    @Override
    public String toString() {
        return name + "  ·  " + protocol.label() + "  ·  " + endpoint();
    }
}
