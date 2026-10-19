package app.perroute.inspector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XrayLogParser {
    private XrayLogParser() {}

    public sealed interface Entry permits Access, Request, Sniffed, RuleHit, Failure {}

    public record Access(String sourceHost, int sourcePort, String network, String destinationHost, int destinationPort,
                         String inbound, String outbound) implements Entry {}

    public record Request(String session, String network, String destinationHost, int destinationPort) implements Entry {}

    public record Sniffed(String session, String domain) implements Entry {}

    public record RuleHit(String session, String ruleTag, String outbound, String destinationHost, int destinationPort) implements Entry {}

    public record Failure(String session, String detail) implements Entry {}

    private static final Pattern ACCESS = Pattern.compile(
            "from (?:(?:tcp|udp):)?(\\S+?):(\\d+) accepted (tcp|udp):(.+):(\\d+) \\[([^\\]\\s]+) (?:->|>>) ([^\\]\\s]+)\\]");
    private static final Pattern REQUEST = Pattern.compile(
            "\\[(\\d+)\\] proxy/socks: (?:TCP Connect|UDP) request to (tcp|udp):(.+):(\\d+)\\s*$");
    private static final Pattern SNIFFED = Pattern.compile("\\[(\\d+)\\] app/dispatcher: sniffed domain: (\\S+)");
    private static final Pattern RULE = Pattern.compile(
            "\\[(\\d+)\\] app/dispatcher: (?:Hit route rule: \\[(.*?)\\] so )?taking detour \\[(.*?)\\] for \\[(?:tcp|udp):(.+):(\\d+)\\]");
    private static final Pattern FAILURE = Pattern.compile(
            "\\[(?:Warning|Error)\\] \\[(\\d+)\\] (.*(?:failed|failure|rejected|timeout|closed).*)$", Pattern.CASE_INSENSITIVE);

    public static Entry parse(String line) {
        if (line == null || line.length() < 20) return null;
        Matcher matcher;
        if (line.contains(" accepted ") && (matcher = ACCESS.matcher(line)).find()) {
            return new Access(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3),
                    unbracket(matcher.group(4)), Integer.parseInt(matcher.group(5)), matcher.group(6), matcher.group(7));
        }
        if (line.contains("taking detour") && (matcher = RULE.matcher(line)).find()) {
            return new RuleHit(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2), matcher.group(3),
                    unbracket(matcher.group(4)), Integer.parseInt(matcher.group(5)));
        }
        if (line.contains("sniffed domain") && (matcher = SNIFFED.matcher(line)).find()) {
            return new Sniffed(matcher.group(1), matcher.group(2));
        }
        if (line.contains("proxy/socks") && (matcher = REQUEST.matcher(line)).find()) {
            return new Request(matcher.group(1), matcher.group(2), unbracket(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        }
        if ((line.contains("[Warning]") || line.contains("[Error]")) && (matcher = FAILURE.matcher(line)).find()) {
            return new Failure(matcher.group(1), shorten(matcher.group(2)));
        }
        return null;
    }

    private static String unbracket(String host) {
        return host.length() > 2 && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    static String shorten(String detail) {
        String text = detail.trim();
        int arrow = text.lastIndexOf(" > ");
        if (arrow >= 0 && arrow + 3 < text.length()) text = text.substring(arrow + 3);
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
