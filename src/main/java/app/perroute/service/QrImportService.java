package app.perroute.service;

import com.github.sarxos.webcam.Webcam;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public final class QrImportService {
    public String decode(Path imageFile) throws Exception {
        BufferedImage image = ImageIO.read(imageFile.toFile());
        if (image == null) throw new IOException("The selected file is not a supported image");
        return decode(image);
    }

    public String decodeClipboard() throws Exception {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            Image image = (Image)clipboard.getData(DataFlavor.imageFlavor);
            BufferedImage buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
            var graphics = buffered.createGraphics();
            try { graphics.drawImage(image, 0, 0, null); } finally { graphics.dispose(); }
            return decode(buffered);
        }
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            String text = String.valueOf(clipboard.getData(DataFlavor.stringFlavor)).trim();
            if (!text.isBlank()) return text;
        }
        throw new IOException("Clipboard does not contain a QR image or configuration text");
    }

    public String decodeCamera() throws Exception {
        Webcam webcam = Webcam.getDefault();
        if (webcam == null) throw new IOException("No camera was found");
        try {
            webcam.open();
            long deadline = System.currentTimeMillis() + 12_000;
            String lastError = "No QR code was visible";
            while (System.currentTimeMillis() < deadline) {
                BufferedImage image = webcam.getImage();
                if (image != null) {
                    try { return decode(image); }
                    catch (NotFoundException error) { lastError = "Hold the QR code steady in front of the camera"; }
                }
                Thread.sleep(250);
            }
            throw new IOException(lastError);
        } finally {
            webcam.close();
        }
    }

    private String decode(BufferedImage image) throws NotFoundException {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        String text = new MultiFormatReader().decode(bitmap).getText();
        if (text == null || text.isBlank()) throw NotFoundException.getNotFoundInstance();
        return text.trim();
    }
}
