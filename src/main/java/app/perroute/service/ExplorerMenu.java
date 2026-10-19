package app.perroute.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ExplorerMenu {
    public static final String VERB = "PerRoute.Route";
    public static final String LABEL = "Route with PerRoute";
    private static final List<String> CLASSES = List.of("exefile", "lnkfile");

    private ExplorerMenu() {}

    static String key(String fileClass) {
        return "HKEY_CURRENT_USER\\Software\\Classes\\" + fileClass + "\\shell\\" + VERB;
    }

    public static String command(Path executable) {
        return "\"" + executable + "\" " + QuickRouteRequests.ROUTE_ARGUMENT + " \"%1\"";
    }

    public static String registryFile(Path executable) {
        StringBuilder text = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
        for (String fileClass : CLASSES) {
            text.append("\r\n[").append(key(fileClass)).append("]\r\n");
            text.append("\"MUIVerb\"=").append(quote(LABEL)).append("\r\n");
            text.append("\"Icon\"=").append(quote(executable + ",0")).append("\r\n");
            text.append("\r\n[").append(key(fileClass)).append("\\command]\r\n");
            text.append("@=").append(quote(command(executable))).append("\r\n");
        }
        return text.toString();
    }

    public static List<List<String>> unregisterCommands() {
        return CLASSES.stream().map(fileClass -> List.of("reg.exe", "delete", key(fileClass), "/f")).toList();
    }

    public static boolean registeredFor(Path executable) {
        for (String fileClass : CLASSES) {
            Result result = run(List.of("reg.exe", "query", key(fileClass) + "\\command", "/ve"));
            if (result.exitCode() != 0 || !result.output().contains(command(executable))) return false;
        }
        return true;
    }

    public static void register(Path executable) throws IOException {
        Path file = Files.createTempFile("perroute-explorer-", ".reg");
        try {
            byte[] body = registryFile(executable).getBytes(StandardCharsets.UTF_16LE);
            byte[] withMark = new byte[body.length + 2];
            withMark[0] = (byte) 0xFF;
            withMark[1] = (byte) 0xFE;
            System.arraycopy(body, 0, withMark, 2, body.length);
            Files.write(file, withMark);
            Result result = run(List.of("reg.exe", "import", file.toString()));
            if (result.exitCode() != 0) throw new IOException("Windows did not accept the Explorer menu entry: " + result.output().trim());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public static void unregister() {
        for (List<String> command : unregisterCommands()) run(command);
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Result run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "timed out");
            }
            return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return new Result(-1, String.valueOf(error.getMessage()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    private record Result(int exitCode, String output) {}
}
