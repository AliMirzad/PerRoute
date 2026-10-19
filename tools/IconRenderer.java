import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class IconRenderer {
    private static final Color CHARCOAL = new Color(0x1E, 0x20, 0x20);
    private static final Color LIME = new Color(0xD8, 0xF2, 0x4A);
    private static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: IconRenderer <output folder>");
        Path folder = Path.of(args[0]);
        Files.createDirectories(folder);
        ImageIO.setUseCache(false);
        ImageIO.write(render(1024), "png", folder.resolve("perroute-icon.png").toFile());
        ImageIO.write(render(256), "png", folder.resolve("perroute-icon-256.png").toFile());
        List<byte[]> images = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(render(size), "png", png);
            images.add(png.toByteArray());
        }
        writeIco(folder.resolve("perroute.ico"), images);
    }

    static BufferedImage render(int size) {
        int scale = 4;
        int big = size * scale;
        BufferedImage canvas = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double markShare = size <= 24 ? .82 : size <= 48 ? .78 : .74;
        double corner = big * (size <= 24 ? .18 : .24);
        g.setColor(CHARCOAL);
        g.fill(new RoundRectangle2D.Double(0, 0, big, big, corner * 2, corner * 2));
        double w = big * markShare;
        double left = (big - w) / 2, top = (big - w) / 2;
        double stroke = w * (size <= 24 ? .13 : .10);
        double dot = w * (size <= 24 ? .14 : .115);
        double cx = left + w * .5;
        g.setColor(LIME);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(cx, top + w * .14, cx, top + w * .86));
        g.draw(new Line2D.Double(cx, top + w * .46, left + w * .2, top + w * .46));
        g.draw(new Line2D.Double(cx, top + w * .62, left + w * .8, top + w * .62));
        dot(g, cx, top + w * .12, dot);
        dot(g, cx, top + w * .88, dot);
        dot(g, left + w * .14, top + w * .46, dot);
        dot(g, left + w * .86, top + w * .62, dot);
        g.dispose();
        return reduce(canvas, size);
    }

    private static void dot(Graphics2D g, double x, double y, double radius) {
        g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static BufferedImage reduce(BufferedImage source, int size) {
        BufferedImage current = source;
        while (current.getWidth() > size) {
            int next = Math.max(size, current.getWidth() / 2);
            BufferedImage smaller = new BufferedImage(next, next, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = smaller.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, next, next, null);
            g.dispose();
            current = smaller;
        }
        return current;
    }

    private static void writeIco(Path target, List<byte[]> images) throws Exception {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(target))) {
            shortLE(out, 0); shortLE(out, 1); shortLE(out, images.size());
            int offset = 6 + images.size() * 16;
            for (int i = 0; i < images.size(); i++) {
                int size = ICO_SIZES[i];
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(0); out.writeByte(0);
                shortLE(out, 1); shortLE(out, 32);
                intLE(out, images.get(i).length); intLE(out, offset);
                offset += images.get(i).length;
            }
            for (byte[] image : images) out.write(image);
        }
    }

    private static void shortLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff); out.writeByte((value >>> 8) & 0xff);
    }

    private static void intLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff); out.writeByte((value >>> 8) & 0xff);
        out.writeByte((value >>> 16) & 0xff); out.writeByte((value >>> 24) & 0xff);
    }
}
