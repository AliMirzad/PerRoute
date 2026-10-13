package app.perroute.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

@SuppressWarnings("serial")
final class GlassPanel extends JPanel {
    private final int radius;
    private final Color fill;

    GlassPanel() {
        this(26, new Color(255, 255, 255, 178));
    }

    GlassPanel(int radius, Color fill) {
        this.radius = radius;
        this.fill = fill;
        setOpaque(false);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(33, 53, 90, 15));
        g.fillRoundRect(3, 5, Math.max(0, getWidth() - 6), Math.max(0, getHeight() - 7), radius, radius);
        g.setColor(fill);
        g.fillRoundRect(1, 1, Math.max(0, getWidth() - 3), Math.max(0, getHeight() - 4), radius, radius);
        g.setColor(new Color(255, 255, 255, 205));
        g.drawRoundRect(1, 1, Math.max(0, getWidth() - 3), Math.max(0, getHeight() - 4), radius, radius);
        g.dispose();
        super.paintComponent(graphics);
    }
}
