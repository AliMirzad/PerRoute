package app.perroute.parser;

import app.perroute.model.ProxyNode;
import app.perroute.util.Codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SubscriptionParser {
    private final ShareLinkParser linkParser = new ShareLinkParser();
    private final XrayJsonSubscriptionParser jsonParser = new XrayJsonSubscriptionParser();

    public ParseResult parse(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isBlank()) return new ParseResult(List.of(), List.of("Subscription is empty"));
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                return jsonParser.parse(text);
            } catch (RuntimeException e) {
                return new ParseResult(List.of(), List.of("Invalid Xray JSON subscription: " + safeMessage(e)));
            }
        }
        if (!containsSupportedLink(text)) {
            try {
                String decoded = Codec.decodeBase64Text(text).strip();
                if (containsSupportedLink(decoded)) text = decoded;
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<ProxyNode> nodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\\R")) {
            lineNo++;
            String candidate = line.strip();
            if (candidate.isBlank() || candidate.startsWith("#")) continue;
            if (!isSupportedLink(candidate)) {
                if (candidate.contains("://")) warnings.add("Line " + lineNo + ": unsupported protocol");
                continue;
            }
            try {
                nodes.add(linkParser.parse(candidate));
            } catch (RuntimeException e) {
                warnings.add("Line " + lineNo + ": " + safeMessage(e));
            }
        }
        if (nodes.isEmpty() && warnings.isEmpty()) warnings.add("No supported VLESS, VMess, Trojan or Shadowsocks links were found");
        return new ParseResult(nodes.stream().distinct().toList(), warnings);
    }

    private static boolean containsSupportedLink(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("vless://") || lower.contains("vmess://") ||
                lower.contains("trojan://") || lower.contains("ss://");
    }

    private static boolean isSupportedLink(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("vless://") || lower.startsWith("vmess://") ||
                lower.startsWith("trojan://") || lower.startsWith("ss://");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
