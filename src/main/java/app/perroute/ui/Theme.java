package app.perroute.ui;

import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

public final class Theme {
    static final Color BACKGROUND = new Color(0xEFF5FF);
    static final Color SURFACE = Color.WHITE;
    static final Color PRIMARY = new Color(0x5865F2);
    static final Color PRIMARY_DARK = new Color(0x414CCB);
    static final Color SIDEBAR = new Color(0x101827);
    static final Color SIDEBAR_CARD = new Color(0x1B263A);
    static final Color TEXT = new Color(0x17223B);
    static final Color MUTED = new Color(0x6C7891);
    static final Color SUCCESS = new Color(0x138A5B);
    static final Color DANGER = new Color(0xC43D4B);
    static final Color BORDER = new Color(0xDCE3EE);

    private Theme() {}

    public static void install() {
        System.setProperty("flatlaf.useWindowDecorations", "true");
        FlatLightLaf.setup();
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font);
        UIManager.put("TextField.font", font);
        UIManager.put("TextArea.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("Table.font", font);
        UIManager.put("TableHeader.font", font.deriveFont(Font.BOLD));
        UIManager.put("TabbedPane.font", font.deriveFont(Font.BOLD));
        UIManager.put("OptionPane.messageFont", font);
        UIManager.put("OptionPane.buttonFont", font);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("PopupMenu.background", new Color(0xF8FAFF));
        UIManager.put("List.background", new Color(0xF8FAFF));
        UIManager.put("List.selectionBackground", new Color(0xE1E6FF));
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.selectionBackground", new Color(0xDCE5FF));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TextField.margin", new Insets(7, 9, 7, 9));
        UIManager.put("Component.arc", 14);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabHeight", 42);
        UIManager.put("TabbedPane.selectedBackground", SURFACE);
        UIManager.put("TitlePane.unifiedBackground", true);
    }

    static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(PRIMARY);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.putClientProperty("FlatLaf.style", "arc: 18; font: +1; borderWidth: 0; focusWidth: 0");
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PRIMARY_DARK),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        return button;
    }

    static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(new Color(0xEEF2FF));
        button.setForeground(PRIMARY_DARK);
        button.setFocusPainted(false);
        button.putClientProperty("FlatLaf.style", "arc: 18; borderWidth: 0; focusWidth: 0");
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return button;
    }

    static Border cardBorder() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(14, 14, 14, 14));
    }

    static void card(JComponent component) {
        component.setBackground(SURFACE);
        component.setBorder(cardBorder());
        component.putClientProperty("FlatLaf.style", "arc: 18; background: #ffffff");
    }
}
