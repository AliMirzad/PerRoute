import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class IconPacker {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: IconPacker input.png output.ico");
        ImageIO.setUseCache(false);
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IllegalArgumentException("Input is not a PNG image");
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
            graphics.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", png);
            images.add(png.toByteArray());
        }

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            writeShortLE(out, 0); writeShortLE(out, 1); writeShortLE(out, images.size());
            int offset = 6 + images.size() * 16;
            for (int i = 0; i < images.size(); i++) {
                int size = SIZES[i];
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(0); out.writeByte(0);
                writeShortLE(out, 1); writeShortLE(out, 32);
                writeIntLE(out, images.get(i).length); writeIntLE(out, offset);
                offset += images.get(i).length;
            }
            for (byte[] image : images) out.write(image);
        }
    }

    private static void writeShortLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff); out.writeByte((value >>> 8) & 0xff);
    }
    private static void writeIntLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff); out.writeByte((value >>> 8) & 0xff);
        out.writeByte((value >>> 16) & 0xff); out.writeByte((value >>> 24) & 0xff);
    }
}
