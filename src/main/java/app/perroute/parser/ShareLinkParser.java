package app.perroute.parser;

import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.util.Codec;
import app.perroute.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShareLinkParser {
    public ProxyNode parse(String raw) {
        String link = raw == null ? "" : raw.strip();
        if (link.regionMatches(true, 0, "vmess://", 0, 8)) return parseVmess(link);
        if (link.regionMatches(true, 0, "vless://", 0, 8)) return parseUri(link, Protocol.VLESS);
        if (link.regionMatches(true, 0, "trojan://", 0, 9)) return parseUri(link, Protocol.TROJAN);
        if (link.regionMatches(true, 0, "ss://", 0, 5)) return parseShadowsocks(link);
        throw new IllegalArgumentException("Unsupported share-link scheme");
    }

    private ProxyNode parseVmess(String link) {
        String payload = link.substring("vmess://".length()).trim();
        Map<String, Object> json = Json.object(Codec.decodeBase64Text(payload));
        String address = string(json, "add");
        int port = integer(json, "port");
        String id = string(json, "id");
        Map<String, String> options = new LinkedHashMap<>();
        copy(json, options, "scy", "security");
        copy(json, options, "aid", "alterId");
        copy(json, options, "net", "type");
        copy(json, options, "type", "headerType");
        copy(json, options, "host", "host");
        copy(json, options, "path", "path");
        copy(json, options, "tls", "securityLayer");
        copy(json, options, "sni", "sni");
        copy(json, options, "fp", "fingerprint");
        copy(json, options, "alpn", "alpn");
        String name = firstNonBlank(string(json, "ps"), "VMess - " + address);
        return node(name, Protocol.VMESS, address, port, id, options, link);
    }

    private ProxyNode parseUri(String link, Protocol protocol) {
        String rest = link.substring(link.indexOf("://") + 3);
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash); }
        String rawQuery = "";
        int question = rest.indexOf('?');
        if (question >= 0) { rawQuery = rest.substring(question + 1); rest = rest.substring(0, question); }
        int at = rest.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("Credential is missing");
        String credential = lenientDecode(rest.substring(0, at));
        String authority = rest.substring(at + 1);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        HostPort target = hostPort(authority);
        Map<String, String> options = query(rawQuery);
        String name = firstNonBlank(lenientDecode(fragment).strip(), protocol.label() + " - " + target.host);
        return node(name, protocol, target.host, target.port, credential, options, link);
    }

    private static String lenientDecode(String value) {
        try { return Codec.urlDecode(value); }
        catch (IllegalArgumentException malformed) { return value == null ? "" : value; }
    }

    private ProxyNode parseShadowsocks(String link) {
        String body = link.substring(5);
        int hash = body.indexOf('#');
        String name = hash >= 0 ? lenientDecode(body.substring(hash + 1)).strip() : "Shadowsocks";
        if (hash >= 0) body = body.substring(0, hash);
        int query = body.indexOf('?');
        if (query >= 0) body = body.substring(0, query);
        while (body.endsWith("/")) body = body.substring(0, body.length() - 1);

        String decoded = body;
        if (!body.contains("@")) decoded = Codec.decodeBase64Text(body);
        int at = decoded.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("Invalid Shadowsocks link");
        String userInfo = decoded.substring(0, at);
        if (!userInfo.contains(":")) userInfo = Codec.decodeBase64Text(userInfo);
        int separator = userInfo.indexOf(':');
        if (separator < 1) throw new IllegalArgumentException("Invalid Shadowsocks credentials");
        String method = Codec.urlDecode(userInfo.substring(0, separator));
        String password = Codec.urlDecode(userInfo.substring(separator + 1));
        HostPort target = hostPort(decoded.substring(at + 1));
        Map<String, String> options = Map.of("method", method);
        return node(firstNonBlank(name, "Shadowsocks - " + target.host), Protocol.SHADOWSOCKS,
                target.host, target.port, password, options, link);
    }

    private static HostPort hostPort(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0 || close + 2 >= value.length()) throw new IllegalArgumentException("Invalid IPv6 endpoint");
            return new HostPort(value.substring(1, close), Integer.parseInt(value.substring(close + 2)));
        }
        int colon = value.lastIndexOf(':');
        if (colon < 1) throw new IllegalArgumentException("Server port is missing");
        return new HostPort(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = lenientDecode(equals < 0 ? pair : pair.substring(0, equals));
            String value = lenientDecode(equals < 0 ? "" : pair.substring(equals + 1));
            result.put(key, value);
        }
        return result;
    }

    private static ProxyNode node(String name, Protocol protocol, String address, int port,
                                  String credential, Map<String, String> options, String source) {
        String key = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
        return new ProxyNode(key, name, protocol, address, port, credential, options, source);
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static void copy(Map<String, Object> from, Map<String, String> to, String source, String target) {
        String value = string(from, source);
        if (!value.isBlank()) to.put(target, value);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private record HostPort(String host, int port) {}
}
