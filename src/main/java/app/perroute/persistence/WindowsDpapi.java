package app.perroute.persistence;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

final class WindowsDpapi {
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    private static volatile Boolean nativeAvailable;

    private WindowsDpapi() {}

    static byte[] protect(byte[] plain) throws IOException {
        return invoke(plain, true);
    }

    static byte[] unprotect(byte[] encrypted) throws IOException {
        return invoke(encrypted, false);
    }

    private static byte[] invoke(byte[] input, boolean protect) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new IOException("DPAPI storage requires Windows");
        }
        if (!Boolean.FALSE.equals(nativeAvailable)) {
            try {
                byte[] result = invokeNative(input, protect);
                nativeAvailable = true;
                return result;
            } catch (LinkageError | RuntimeException unavailable) {
                nativeAvailable = false;
            }
        }
        return invokePowerShell(input, protect);
    }

    public static class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;

        DataBlob() {}

        DataBlob(byte[] data) {
            pbData = new Memory(Math.max(1, data.length));
            pbData.write(0, data, 0, data.length);
            cbData = data.length;
        }

        @Override protected java.util.List<String> getFieldOrder() { return java.util.List.of("cbData", "pbData"); }
    }

    private interface Crypt32 extends Library {
        boolean CryptProtectData(DataBlob dataIn, Pointer description, DataBlob entropy, Pointer reserved,
                                 Pointer prompt, int flags, DataBlob dataOut);
        boolean CryptUnprotectData(DataBlob dataIn, Pointer description, DataBlob entropy, Pointer reserved,
                                   Pointer prompt, int flags, DataBlob dataOut);
    }

    private interface Kernel32 extends Library {
        Pointer LocalFree(Pointer memory);
    }

    private static final class Bindings {
        static final Crypt32 CRYPT32;
        static final Kernel32 KERNEL32;
        static {
            String resources = System.getProperty("compose.application.resources.dir");
            if (resources != null && System.getProperty("jna.boot.library.path") == null
                    && Files.isRegularFile(Path.of(resources, "jnidispatch.dll"))) {
                System.setProperty("jna.boot.library.path", resources);
            }
            CRYPT32 = Native.load("crypt32", Crypt32.class);
            KERNEL32 = Native.load("kernel32", Kernel32.class);
        }
    }

    private static byte[] invokeNative(byte[] input, boolean protect) throws IOException {
        DataBlob in = new DataBlob(input);
        DataBlob out = new DataBlob();
        boolean ok = protect
                ? Bindings.CRYPT32.CryptProtectData(in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)
                : Bindings.CRYPT32.CryptUnprotectData(in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok) {
            throw new IOException("Windows DPAPI failed (error " + Native.getLastError() + ")");
        }
        try {
            return out.cbData == 0 ? new byte[0] : out.pbData.getByteArray(0, out.cbData);
        } finally {
            if (out.pbData != null) Bindings.KERNEL32.LocalFree(out.pbData);
        }
    }

    private static byte[] invokePowerShell(byte[] input, boolean protect) throws IOException {
        String method = protect ? "Protect" : "Unprotect";
        String script = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;" +
                "$i=[Convert]::FromBase64String([Console]::In.ReadToEnd());" +
                "$o=[Security.Cryptography.ProtectedData]::" + method +
                "($i,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);" +
                "[Console]::Out.Write([Convert]::ToBase64String($o))";
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Process process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript)
                .redirectErrorStream(true).start();
        try {
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(captured); } catch (IOException ignored) {}
            });
            process.getOutputStream().write(Base64.getEncoder().encode(input));
            process.getOutputStream().close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Windows DPAPI operation timed out");
            }
            drain.join(2_000);
            String output = captured.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IOException("Windows DPAPI failed: " + output);
            try { return Base64.getDecoder().decode(output); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid response from Windows DPAPI", e); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Windows DPAPI operation was interrupted", e);
        }
    }
}
