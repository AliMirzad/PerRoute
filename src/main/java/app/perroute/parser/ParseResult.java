package app.perroute.parser;

import app.perroute.model.ProxyNode;
import java.util.List;

public record ParseResult(List<ProxyNode> nodes, List<String> warnings) {
    public ParseResult {
        nodes = List.copyOf(nodes);
        warnings = List.copyOf(warnings);
    }
}
