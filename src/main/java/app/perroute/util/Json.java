package app.perroute.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.space();
        if (!parser.end()) throw parser.error("Unexpected trailing content");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { quote(s, out); return; }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            out.append("{");
            if (!map.isEmpty()) out.append('\n');
            int index = 0;
            for (var entry : map.entrySet()) {
                indent(out, depth + 1);
                quote(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), out, depth + 1);
                if (++index < map.size()) out.append(',');
                out.append('\n');
            }
            if (!map.isEmpty()) indent(out, depth);
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> list) {
            out.append("[");
            var values = new ArrayList<>();
            list.forEach(values::add);
            if (!values.isEmpty()) out.append('\n');
            for (int i = 0; i < values.size(); i++) {
                indent(out, depth + 1);
                write(values.get(i), out, depth + 1);
                if (i + 1 < values.size()) out.append(',');
                out.append('\n');
            }
            if (!values.isEmpty()) indent(out, depth);
            out.append(']');
            return;
        }
        quote(String.valueOf(value), out);
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static final class Parser {
        private final String text;
        private int pos;
        Parser(String text) { this.text = text == null ? "" : text; }
        boolean end() { return pos >= text.length(); }
        void space() { while (!end() && Character.isWhitespace(text.charAt(pos))) pos++; }
        RuntimeException error(String message) { return new IllegalArgumentException(message + " at position " + pos); }
        Object value() {
            space();
            if (end()) throw error("Unexpected end of JSON");
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        Object literal(String token, Object result) {
            if (!text.startsWith(token, pos)) throw error("Invalid value");
            pos += token.length();
            return result;
        }
        Map<String, Object> object() {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (!end() && text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                space();
                if (end() || text.charAt(pos) != '"') throw error("Expected object key");
                String key = string();
                space();
                if (end() || text.charAt(pos++) != ':') throw error("Expected ':'");
                map.put(key, value());
                space();
                if (!end() && text.charAt(pos) == '}') { pos++; return map; }
                if (end() || text.charAt(pos++) != ',') throw error("Expected ','");
            }
        }
        List<Object> array() {
            pos++;
            List<Object> list = new ArrayList<>();
            space();
            if (!end() && text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                space();
                if (!end() && text.charAt(pos) == ']') { pos++; return list; }
                if (end() || text.charAt(pos++) != ',') throw error("Expected ','");
            }
        }
        String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (end()) throw error("Invalid escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("Invalid unicode escape");
                        out.append((char)Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }
        Number number() {
            int start = pos;
            if (!end() && text.charAt(pos) == '-') pos++;
            while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            if (!end() && text.charAt(pos) == '.') { pos++; while (!end() && Character.isDigit(text.charAt(pos))) pos++; }
            if (!end() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!end() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (start == pos) throw error("Expected value");
            BigDecimal value = new BigDecimal(text.substring(start, pos));
            try { return value.intValueExact(); } catch (ArithmeticException ignored) {}
            try { return value.longValueExact(); } catch (ArithmeticException ignored) {}
            return value.doubleValue();
        }
    }
}
