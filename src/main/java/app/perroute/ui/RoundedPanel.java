package app.perroute.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

@SuppressWarnings("serial")
final class RoundedPanel extends JPanel {
    private final int radius;
    private Color fill;

    RoundedPanel(int radius, Color fill) {
        this.radius = radius;
        this.fill = fill;
        setOpaque(false);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
        g.dispose();
        super.paintComponent(graphics);
    }
}
