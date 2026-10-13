package app.perroute.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;

@SuppressWarnings("serial")
final class GlassBackgroundPanel extends JPanel {
    GlassBackgroundPanel() {
        setOpaque(false);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0, 0), new Point2D.Float(getWidth(), getHeight()),
                new float[]{0f, .52f, 1f},
                new Color[]{new Color(0xEAF3FF), new Color(0xF7F5FF), new Color(0xE9FBF8)}));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(93, 110, 255, 34));
        g.fillOval(-110, -120, 430, 430);
        g.setColor(new Color(88, 224, 195, 34));
        g.fillOval(getWidth() - 330, getHeight() - 340, 450, 450);
        g.setColor(new Color(184, 113, 255, 24));
        g.fillOval(getWidth() / 2 - 90, -170, 420, 420);
        g.dispose();
        super.paintComponent(graphics);
    }
}
