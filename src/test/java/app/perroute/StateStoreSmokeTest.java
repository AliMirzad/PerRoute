package app.perroute;

import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.RoutingMode;
import app.perroute.parser.ShareLinkParser;
import app.perroute.parser.SubscriptionParser;
import app.perroute.persistence.AppStateStore;

import java.nio.file.Files;
import java.nio.file.Path;

public final class StateStoreSmokeTest {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(System.getProperty("user.dir"), "build").toAbsolutePath();
        Files.createDirectories(directory);
        Path file = directory.resolve("state-smoke.bin");
        try {
            AppState original = new AppState();
            original.nodes().add(new ShareLinkParser().parse("trojan://secret@example.com:443?security=tls#State"));
            String profileJson = "[{\"remarks\":\"Profile\",\"outbounds\":[{\"tag\":\"p\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"profile.example\",\"port\":443,\"users\":[{\"id\":\"33333333-3333-3333-3333-333333333333\",\"encryption\":\"none\"}]}]}}]}]";
            original.nodes().add(new SubscriptionParser().parse(profileJson).nodes().get(0));
            original.appPaths().add("C:\\Apps\\Example.exe");
            original.subscriptionUrls().add("https://example.com/sub");
            original.nodeSources().put(original.nodes().get(0).key(), "https://example.com/sub");
            original.nodeSources().put(original.nodes().get(1).key(), "__manual__");
            original.setRoutingMode(RoutingMode.EXCLUDE_SELECTED);
            original.setConnectionMode(ConnectionMode.TUN_COMPATIBILITY);
            original.setSelectedNodeKey(original.nodes().get(0).key());
            AppStateStore store = new AppStateStore(file);
            store.save(original);
            AppState restored = store.load();
            if (restored.nodes().size() != 2 || restored.appPaths().size() != 1 ||
                    restored.routingMode() != RoutingMode.EXCLUDE_SELECTED ||
                    restored.connectionMode() != ConnectionMode.TUN_COMPATIBILITY ||
                    !restored.selectedNodeKey().equals(original.selectedNodeKey()) ||
                    !"https://example.com/sub".equals(restored.nodeSources().get(restored.nodes().get(0).key())) ||
                    !"__manual__".equals(restored.nodeSources().get(restored.nodes().get(1).key())) ||
                    restored.nodes().get(1).option("rawProfileJson").isBlank()) {
                throw new AssertionError("Encrypted state did not round-trip");
            }
            String raw = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (raw.contains("secret") || raw.contains("example.com")) throw new AssertionError("State was stored in plaintext");
            System.out.println("Encrypted state round-trip passed.");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".key"));
        }
    }
}
