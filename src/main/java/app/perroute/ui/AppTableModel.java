package app.perroute.ui;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

@SuppressWarnings("serial")
final class AppTableModel extends AbstractTableModel {
    private final List<String> paths;
    private final String[] columns = {"برنامه", "مسیر فایل اجرایی"};
    AppTableModel(List<String> paths) { this.paths = paths; }
    String get(int row) { return paths.get(row); }
    void addAll(Collection<String> values) {
        for (String value : values) if (paths.stream().noneMatch(value::equalsIgnoreCase)) paths.add(value);
        fireTableDataChanged();
    }
    void remove(int row) { paths.remove(row); fireTableRowsDeleted(row, row); }
    @Override public int getRowCount() { return paths.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Object getValueAt(int row, int column) {
        String path = paths.get(row);
        return column == 0 ? Path.of(path).getFileName() : path;
    }
}
