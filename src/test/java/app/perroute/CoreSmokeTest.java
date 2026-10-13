package app.perroute;

import app.perroute.parser.ShareLinkParser;
import app.perroute.service.AppDirectories;
import app.perroute.service.CoreInstaller;
import app.perroute.service.XrayConfigGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class CoreSmokeTest {
    public static void main(String[] args) throws Exception {
        CoreInstaller installer = new CoreInstaller();
        if (!installer.inspect().ready()) installer.install(System.out::println);
        String vmessJson = "{\"v\":\"2\",\"ps\":\"VM\",\"add\":\"example.com\",\"port\":\"443\",\"id\":\"22222222-2222-2222-2222-222222222222\",\"scy\":\"auto\",\"net\":\"grpc\",\"path\":\"svc\",\"tls\":\"tls\",\"sni\":\"example.com\"}";
        String vmess = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(vmessJson.getBytes(StandardCharsets.UTF_8));
        List<String> links = List.of(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&security=tls&sni=example.com&path=%2Fvpn#VLESS",
                vmess,
                "trojan://secret@example.com:443?security=tls&sni=example.com#Trojan",
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388#SS"
        );
        Path smokeDirectory = Path.of(System.getProperty("user.dir"), "build").toAbsolutePath();
        Files.createDirectories(smokeDirectory);
        Path file = Files.createTempFile(smokeDirectory, "perroute-smoke-", ".json");
        try {
            for (String link : links) validate(link, file);
            System.out.println("Xray accepted generated configs for VLESS, VMess, Trojan and Shadowsocks.");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @SuppressWarnings("unchecked")
    private static void validate(String link, Path file) throws Exception {
        String config = new XrayConfigGenerator().generateApplicationProxy(new ShareLinkParser().parse(link));
        Files.writeString(file, config, StandardCharsets.UTF_8);
        Path directory = AppDirectories.coreDirectory();
        Process process = new ProcessBuilder(directory.resolve("xray.exe").toString(), "run", "-test", "-config", file.toString())
                .directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Xray validation timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError("Xray rejected config:\n" + output + "\n" + config);
    }
}
