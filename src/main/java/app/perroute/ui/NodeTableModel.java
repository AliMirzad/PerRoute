package app.perroute.ui;

import app.perroute.model.ProxyNode;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SuppressWarnings("serial")
final class NodeTableModel extends AbstractTableModel {
    private final List<ProxyNode> nodes;
    private final String[] columns = {"نام سرور", "پروتکل", "آدرس"};

    NodeTableModel(List<ProxyNode> nodes) { this.nodes = nodes; }
    ProxyNode get(int row) { return nodes.get(row); }
    List<ProxyNode> values() { return List.copyOf(nodes); }
    void replace(Collection<ProxyNode> values) { nodes.clear(); nodes.addAll(values); fireTableDataChanged(); }
    void remove(int row) { nodes.remove(row); fireTableRowsDeleted(row, row); }
    @Override public int getRowCount() { return nodes.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Object getValueAt(int row, int column) {
        ProxyNode node = nodes.get(row);
        return switch (column) {
            case 0 -> displayName(node.name());
            case 1 -> node.protocol().label();
            case 2 -> node.endpoint();
            default -> "";
        };
    }

    private static String displayName(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(codePoint -> codePoint <= Character.MAX_VALUE && codePoint != 0xFE0F)
                .forEach(result::appendCodePoint);
        return result.toString().replace("□", "").replace("→", "").replace("←", "")
                .replaceAll("\\s+", " ").strip();
    }
}
