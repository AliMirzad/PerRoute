package app.perroute.parser;

import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class XrayJsonSubscriptionParser {
    ParseResult parse(String content) {
        Object parsed = Json.parse(content);
        List<?> profiles = parsed instanceof List<?> list ? list : List.of(parsed);
        List<ProxyNode> nodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int index = 0;
        for (Object value : profiles) {
            index++;
            if (!(value instanceof Map<?, ?> raw)) {
                warnings.add("JSON item " + index + " is not an Xray config object");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) raw;
            Endpoint endpoint = firstEndpoint(config);
            if (endpoint == null) {
                warnings.add("JSON item " + index + " has no supported proxy outbound");
                continue;
            }
            String rawJson = Json.stringify(config);
            String name = string(config.get("remarks"));
            if (name.isBlank()) name = "Xray Profile " + index;
            String key = UUID.nameUUIDFromBytes(rawJson.getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, String> options = new LinkedHashMap<>();
            options.put("rawProfileJson", rawJson);
            nodes.add(new ProxyNode(key, name, Protocol.XRAY_PROFILE, endpoint.address, endpoint.port,
                    "embedded-profile", options, ""));
        }
        if (nodes.isEmpty() && warnings.isEmpty()) warnings.add("The JSON subscription contains no Xray profiles");
        return new ParseResult(nodes, warnings);
    }

    private Endpoint firstEndpoint(Map<String, Object> config) {
        Object rawOutbounds = config.get("outbounds");
        if (!(rawOutbounds instanceof List<?> outbounds)) return null;
        for (Object value : outbounds) {
            if (!(value instanceof Map<?, ?> outbound)) continue;
            String protocol = string(outbound.get("protocol")).toLowerCase(Locale.ROOT);
            if (!List.of("vless", "vmess", "trojan", "shadowsocks").contains(protocol)) continue;
            Object rawSettings = outbound.get("settings");
            if (!(rawSettings instanceof Map<?, ?> settings)) continue;
            Endpoint endpoint = endpointFromSettings(settings);
            if (endpoint != null) return endpoint;
        }
        return null;
    }

    private Endpoint endpointFromSettings(Map<?, ?> settings) {
        String address = string(settings.get("address"));
        int port = integer(settings.get("port"));
        if (!address.isBlank() && port > 0) return new Endpoint(address, port);
        Endpoint fromVnext = endpointFromList(settings.get("vnext"));
        return fromVnext != null ? fromVnext : endpointFromList(settings.get("servers"));
    }

    private Endpoint endpointFromList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> server)) return null;
        String address = string(server.get("address"));
        int port = integer(server.get("port"));
        return !address.isBlank() && port > 0 ? new Endpoint(address, port) : null;
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(string(value)); } catch (NumberFormatException ignored) { return -1; }
    }
    private record Endpoint(String address, int port) {}
}
