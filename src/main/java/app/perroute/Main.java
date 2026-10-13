package app.perroute;

import app.perroute.model.AppState;
import app.perroute.persistence.AppStateStore;
import app.perroute.service.AppDirectories;
import app.perroute.service.SingleInstanceLock;
import app.perroute.ui.SimpleMainFrame;
import app.perroute.ui.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;

public final class Main {
    private static SingleInstanceLock instanceLock;
    private Main() {}

    public static void main(String[] args) {
        Theme.install();
        var dataDirectory = AppDirectories.dataDirectory();
        try {
            instanceLock = SingleInstanceLock.acquire(dataDirectory);
            if (instanceLock == null) {
                JOptionPane.showMessageDialog(null,
                        "PerRoute از قبل باز است. همان پنجره را استفاده کنید.",
                        "PerRoute", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> instanceLock.close()));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "قفل اجرای PerRoute ساخته نشد: " + e.getMessage(),
                    "PerRoute", JOptionPane.ERROR_MESSAGE);
            return;
        }
        AppStateStore store = new AppStateStore(dataDirectory.resolve("state.bin"));
        AppState state;
        try {
            state = store.load();
        } catch (IOException e) {
            state = new AppState();
            JOptionPane.showMessageDialog(null,
                    "تنظیمات قبلی قابل خواندن نبود و برنامه با تنظیمات خالی اجرا شد.\n" + e.getMessage(),
                    "PerRoute", JOptionPane.WARNING_MESSAGE);
        }
        AppState finalState = state;
        SwingUtilities.invokeLater(() -> new SimpleMainFrame(finalState, store).setVisible(true));
    }
}
