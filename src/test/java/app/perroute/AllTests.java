package app.perroute;

import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.model.RouteRule;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ConnectionProfile;
import app.perroute.persistence.AppStateStore;
import app.perroute.parser.ParseResult;
import app.perroute.parser.ShareLinkParser;
import app.perroute.parser.SubscriptionParser;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.AppProxyLauncher;
import app.perroute.service.ProxiFyreManager;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.RouteRuleEvaluator;
import app.perroute.service.TunnelHttp;
import app.perroute.service.TunnelSpeedTest;
import app.perroute.service.ServerLatencyProbe;
import app.perroute.service.SecureUpdateService;
import app.perroute.service.TrafficHistoryStore;
import app.perroute.service.QrImportService;
import app.perroute.service.ProcessDiscovery;
import app.perroute.service.InstalledApplicationDiscovery;
import app.perroute.util.Codec;
import app.perroute.util.Json;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import javax.imageio.ImageIO;

public final class AllTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        run("VLESS parsing", AllTests::testVless);
        run("VMess parsing", AllTests::testVmess);
        run("Trojan parsing", AllTests::testTrojan);
        run("Shadowsocks parsing", AllTests::testShadowsocks);
        run("Subscription decoding", AllTests::testSubscriptionBase64);
        run("Xray JSON import", AllTests::testXrayJsonSubscription);
        run("Portable config export", AllTests::testPortableConfigExport);
        run("Duplicate identity", AllTests::testDuplicateIdentity);
        run("Include routing", AllTests::testConfigIncludeMode);
        run("Exclude routing", AllTests::testConfigExcludeMode);
        run("Local proxy config", AllTests::testApplicationProxyConfig);
        run("Per-app rules", AllTests::testPerAppRoutingRules);
        run("Launch plans", AllTests::testAppProxyLaunchPlans);
        run("Native per-app config", AllTests::testNativePerAppConfig);
        run("Tunnel health check", AllTests::testProxyHealthCheck);
        run("Latency probe", AllTests::testServerLatencyProbe);
        run("Application discovery", AllTests::testApplicationDiscoveryDeduplication);
        run("Signed updates", AllTests::testSignedUpdateManifest);
        run("Traffic history", AllTests::testTrafficHistory);
        run("QR import", AllTests::testQrImport);
        run("Version 1 state", AllTests::testVersionOneStateRoundTrip);
        run("JSON round trip", AllTests::testJsonRoundTrip);
        run("Lenient share links", AllTests::testLenientShareLinks);
        run("Route rule evaluator", AllTests::testRouteRuleEvaluator);
        run("Speed measurement", AllTests::testSpeedMeasurement);
        System.out.println("All tests passed (" + assertions + " assertions)");
        System.exit(0);
    }

    @FunctionalInterface
    private interface CheckedTest { void run() throws Exception; }

    private static void run(String name, CheckedTest test) throws Exception {
        System.out.println("[test] " + name);
        test.run();
    }

    private static void testVless() {
        ProxyNode node = new ShareLinkParser().parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&security=tls&sni=edge.example.com&path=%2Fvpn&host=cdn.example.com#My%20Node");
        equal(Protocol.VLESS, node.protocol());
        equal("example.com", node.address());
        equal(443, node.port());
        equal("My Node", node.name());
        equal("/vpn", node.option("path"));
    }

    private static void testVmess() {
        String json = "{\"v\":\"2\",\"ps\":\"VM Test\",\"add\":\"vm.example\",\"port\":\"8443\",\"id\":\"22222222-2222-2222-2222-222222222222\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"grpc\",\"path\":\"svc\",\"tls\":\"tls\",\"sni\":\"vm.example\"}";
        String link = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ProxyNode node = new ShareLinkParser().parse(link);
        equal(Protocol.VMESS, node.protocol());
        equal("vm.example", node.address());
        equal("grpc", node.option("type"));
        equal("VM Test", node.name());
    }

    private static void testTrojan() {
        ProxyNode node = new ShareLinkParser().parse("trojan://secret@trojan.example:443?security=tls&sni=trojan.example#Trojan");
        equal(Protocol.TROJAN, node.protocol());
        equal("secret", node.credential());
    }

    private static void testShadowsocks() {
        String user = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:password".getBytes(StandardCharsets.UTF_8));
        ProxyNode node = new ShareLinkParser().parse("ss://" + user + "@ss.example:8388#SS");
        equal(Protocol.SHADOWSOCKS, node.protocol());
        equal("aes-256-gcm", node.option("method"));
        equal("password", node.credential());
    }

    private static void testSubscriptionBase64() {
        String links = "vless://id@one.example:443?security=tls#One\n" +
                "trojan://pass@two.example:443?security=tls#Two\n";
        String subscription = Base64.getEncoder().withoutPadding().encodeToString(links.getBytes(StandardCharsets.UTF_8));
        ParseResult result = new SubscriptionParser().parse(subscription);
        equal(2, result.nodes().size());
        equal(0, result.warnings().size());
    }

    private static void testPortableConfigExport() {
        String link = "vless://id@copy.example:443?security=tls#Copy";
        equal(link, new ShareLinkParser().parse(link).exportText());

        String json = """
                {"remarks":"Copy JSON","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"json-copy.example","port":443,"users":[{"id":"id"}]}]}}]}
                """;
        ProxyNode profile = new SubscriptionParser().parse(json).nodes().get(0);
        equal(Json.parse(json), Json.parse(profile.exportText()));
    }

    private static void testDuplicateIdentity() {
        ProxyNode first = new ShareLinkParser().parse("vless://same-id@duplicate.example:443?security=tls&type=ws&path=%2Fvpn#First%20name");
        ProxyNode renamed = new ShareLinkParser().parse("vless://same-id@duplicate.example:443?security=tls&type=ws&path=%2Fvpn#Another%20name");
        equal(first.identity(), renamed.identity());

        String profileA = """
                {"remarks":"First","log":{"access":"/mobile/a.log"},"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"same-json.example","port":443,"users":[{"id":"same-id"}]}]}}]}
                """;
        String profileB = profileA.replace("First", "Renamed").replace("/mobile/a.log", "C:/different.log");
        ProxyNode jsonA = new SubscriptionParser().parse(profileA).nodes().get(0);
        ProxyNode jsonB = new SubscriptionParser().parse(profileB).nodes().get(0);
        equal(jsonA.identity(), jsonB.identity());
    }

    @SuppressWarnings("unchecked")
    private static void testXrayJsonSubscription() {
        String json = """
                [{
                  "remarks":"Balanced profile",
                  "log":{"loglevel":"warning","access":"/private/var/mobile/Containers/Shared/AppGroup/example/logs/access.log","error":"/data/user/0/example/error.log"},
                  "inbounds":[],
                  "outbounds":[
                    {"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"json.example","port":443,"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"mlkem768x25519plus.native.0rtt.LLkO3dzcSol4mV4L-9cOmCKU9onnO6_Ng87hglhbc1g"}]}]},"streamSettings":{"network":"tcp","tcpSettings":{"header":{"type":"none"}}}},
                    {"tag":"direct","protocol":"freedom","settings":{}}
                  ],
                  "routing":{"rules":[]}
                }]
                """;
        ParseResult result = new SubscriptionParser().parse(json);
        equal(1, result.nodes().size());
        ProxyNode profile = result.nodes().get(0);
        equal(Protocol.XRAY_PROFILE, profile.protocol());
        equal("Balanced profile", profile.name());
        equal("json.example", profile.address());
        Map<String, Object> generated = Json.object(new XrayConfigGenerator().generate(profile,
                List.of("C:\\Apps\\Only.exe"), RoutingMode.INCLUDE_SELECTED));
        Map<String, Object> generatedLog = (Map<String, Object>)generated.get("log");
        equal(Map.of("loglevel", "info"), generatedLog);
        List<Object> inbounds = (List<Object>)generated.get("inbounds");
        equal("tun", ((Map<String, Object>)inbounds.get(0)).get("protocol"));
        List<Object> outbounds = (List<Object>)generated.get("outbounds");
        equal(3, outbounds.size());
        List<Object> rules = (List<Object>)((Map<String, Object>)generated.get("routing")).get("rules");
        check(rules.stream().map(Map.class::cast).anyMatch(rule -> "proxy-a".equals(rule.get("outboundTag")) && rule.containsKey("process")),
                "Selected application must target the embedded profile");
        Map<String, Object> applicationProxy = Json.object(new XrayConfigGenerator().generateApplicationProxy(profile));
        equal(Map.of("loglevel", "info"), applicationProxy.get("log"));
        validateWithBundledXray(Json.stringify(applicationProxy));
    }

    private static void validateWithBundledXray(String config) {
        Path xray = Path.of("tools", "xray", "xray.exe").toAbsolutePath().normalize();
        if (!Files.isRegularFile(xray)) return;
        Path temporary = null;
        try {
            Path testTemp = Path.of("build", "tmp").toAbsolutePath().normalize();
            Files.createDirectories(testTemp);
            temporary = Files.createTempFile(testTemp, "perroute-profile-test-", ".json");
            Files.writeString(temporary, config, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(xray.toString(), "run", "-test", "-config", temporary.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            check(exit == 0, "Bundled Xray rejected the ML-KEM profile: " + output);
        } catch (Exception error) {
            throw new AssertionError("Could not validate the ML-KEM profile with bundled Xray", error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void testConfigIncludeMode() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?type=ws&security=tls&sni=example.com&path=%2Fx#One");
        String config = new XrayConfigGenerator().generate(node, List.of("C:\\Apps\\Browser.exe"), RoutingMode.INCLUDE_SELECTED);
        Map<String, Object> root = Json.object(config);
        List<Object> outbounds = (List<Object>) root.get("outbounds");
        equal("vless", ((Map<String, Object>)outbounds.get(0)).get("protocol"));
        Map<String, Object> routing = (Map<String, Object>)root.get("routing");
        List<Object> rules = (List<Object>)routing.get("rules");
        equal("proxy", ((Map<String, Object>)rules.get(1)).get("outboundTag"));
        equal("direct", ((Map<String, Object>)rules.get(2)).get("outboundTag"));
        check(config.contains("C:/Apps/Browser.exe"), "Windows paths must use forward slashes");
        check(config.contains("\"Browser\"") && config.contains("\"browser\""),
                "Process rules must include case variants of the executable name");
    }

    @SuppressWarnings("unchecked")
    private static void testConfigExcludeMode() {
        ProxyNode node = new ShareLinkParser().parse("trojan://pass@example.com:443?security=tls#T");
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generate(node,
                List.of("C:\\Apps\\Direct.exe"), RoutingMode.EXCLUDE_SELECTED));
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        equal("direct", ((Map<String, Object>)rules.get(1)).get("outboundTag"));
        equal("proxy", ((Map<String, Object>)rules.get(2)).get("outboundTag"));
    }

    @SuppressWarnings("unchecked")
    private static void testApplicationProxyConfig() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?security=tls#Isolated");
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generateApplicationProxy(node));
        List<Object> inbounds = (List<Object>)root.get("inbounds");
        equal(3, inbounds.size());
        equal("127.0.0.1", ((Map<String, Object>)inbounds.get(0)).get("listen"));
        equal("socks", ((Map<String, Object>)inbounds.get(0)).get("protocol"));
        equal(AppProxyLauncher.SOCKS_PORT, ((Map<String, Object>)inbounds.get(0)).get("port"));
        equal("http", ((Map<String, Object>)inbounds.get(1)).get("protocol"));
        equal(AppProxyLauncher.HTTP_PORT, ((Map<String, Object>)inbounds.get(1)).get("port"));
        equal("perroute-api", ((Map<String, Object>)inbounds.get(2)).get("tag"));
        check(root.containsKey("stats") && root.containsKey("api"),
                "Application proxy mode must expose loopback traffic statistics");
        check(inbounds.stream().map(Map.class::cast).noneMatch(inbound -> "tun".equals(inbound.get("protocol"))),
                "Application proxy mode must not create a TUN inbound");
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        check(rules.stream().map(Map.class::cast).anyMatch(rule -> "perroute-app-proxy".equals(rule.get("ruleTag")) &&
                        "proxy".equals(rule.get("outboundTag"))),
                "Local application proxy traffic must target the selected server");

        Map<String, Object> smart = Json.object(new XrayConfigGenerator().generateApplicationProxy(node,
                List.of("C:\\Apps\\Chrome.exe", "C:\\Apps\\ChatGPT.exe")));
        List<Object> smartInbounds = (List<Object>)smart.get("inbounds");
        equal(5, smartInbounds.size());
        equal("perroute-app-0", ((Map<String, Object>)smartInbounds.get(2)).get("tag"));
        equal(XrayConfigGenerator.perAppPort(0), ((Map<String, Object>)smartInbounds.get(2)).get("port"));
        equal("perroute-app-1", ((Map<String, Object>)smartInbounds.get(3)).get("tag"));
        equal(XrayConfigGenerator.perAppPort(1), ((Map<String, Object>)smartInbounds.get(3)).get("port"));
        validateWithBundledXray(Json.stringify(smart));
    }

    private static void testAppProxyLaunchPlans() throws Exception {
        AppProxyLauncher launcher = new AppProxyLauncher();

        AppProxyLauncher.LaunchPlan chrome = launcher.plan("C:\\Apps\\chrome.exe");
        equal(AppProxyLauncher.Adapter.CHROMIUM, chrome.compatibility().adapter());
        check(chrome.command().stream().anyMatch(value -> value.startsWith("--proxy-server=http://127.0.0.1:")),
                "Chromium apps must receive their native proxy switch");
        check(!chrome.environment().containsKey("JAVA_TOOL_OPTIONS"),
                "Chromium apps must not receive JVM-only settings");

        AppProxyLauncher.LaunchPlan idea = launcher.plan("C:\\Apps\\idea64.exe");
        equal(AppProxyLauncher.Adapter.JETBRAINS, idea.compatibility().adapter());
        equal(1, idea.command().size());
        check(idea.environment().get("JAVA_TOOL_OPTIONS").contains("https.proxyHost=127.0.0.1"),
                "JetBrains apps must receive JVM proxy properties");

        AppProxyLauncher.LaunchPlan generic = launcher.plan("C:\\Apps\\proxy-aware.exe");
        equal(AppProxyLauncher.Adapter.ENVIRONMENT, generic.compatibility().adapter());
        check(generic.environment().containsKey("HTTP_PROXY") && generic.environment().containsKey("ALL_PROXY"),
                "Generic proxy-aware apps must receive standard proxy environment variables");

        check(!launcher.compatibility("C:\\Apps\\firefox.exe").supported(),
                "Known apps that ignore per-launch proxy settings must be rejected");
    }

    @SuppressWarnings("unchecked")
    private static void testPerAppRoutingRules() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?security=tls#Rules");
        List<String> apps = List.of("C:\\Apps\\Chrome.exe", "C:\\Apps\\ChatGPT.exe");
        RouteRule direct = new RouteRule("rule-1", "Direct intranet", apps.get(0),
                RouteRule.MatchType.DOMAIN, "example.ir,full:api.local", RouteRule.Action.DIRECT, true);
        RouteRule blocked = new RouteRule("rule-2", "Block port", apps.get(1),
                RouteRule.MatchType.PORT, "25", RouteRule.Action.BLOCK, true);
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generateApplicationProxy(node, apps, List.of(direct, blocked)));
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        Map<String, Object> directRule = rules.stream().map(Map.class::cast)
                .filter(rule -> "perroute-custom-rule-1".equals(rule.get("ruleTag"))).findFirst().orElseThrow();
        equal(List.of("perroute-app-0"), directRule.get("inboundTag"));
        equal(List.of("domain:example.ir", "full:api.local"), directRule.get("domain"));
        equal("direct", directRule.get("outboundTag"));
        Map<String, Object> blockRule = rules.stream().map(Map.class::cast)
                .filter(rule -> "perroute-custom-rule-2".equals(rule.get("ruleTag"))).findFirst().orElseThrow();
        equal("25", blockRule.get("port"));
        equal("block", blockRule.get("outboundTag"));
        validateWithBundledXray(Json.stringify(root));
    }

    @SuppressWarnings("unchecked")
    private static void testNativePerAppConfig() {
        Map<String, Object> root = Json.object(new ProxiFyreManager().generateConfig(List.of(
                "C:\\Program Files\\WindowsApps\\OpenAI.ChatGPT\\ChatGPT.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")));
        equal(true, root.get("bypassLan"));
        List<Object> proxies = (List<Object>)root.get("proxies");
        equal(2, proxies.size());
        Map<String, Object> rule = (Map<String, Object>)proxies.get(0);
        equal("127.0.0.1:" + XrayConfigGenerator.perAppPort(0), rule.get("socks5ProxyEndpoint"));
        equal("127.0.0.1:" + XrayConfigGenerator.perAppPort(1),
                ((Map<String, Object>)proxies.get(1)).get("socks5ProxyEndpoint"));
        List<Object> appNames = (List<Object>)rule.get("appNames");
        check(appNames.get(0).toString().endsWith("ChatGPT.exe"),
                "Native mode must match the selected executable path");
        check(appNames.stream().anyMatch(value -> value.toString().endsWith("OpenAI.ChatGPT\\")),
                "Store-app helpers in the same package directory must use the proxy too");
        equal(List.of("TCP", "UDP"), rule.get("supportedProtocols"));
        check(((List<Object>)root.get("excludes")).contains("xray.exe"),
                "The Xray carrier must be excluded to avoid a proxy loop");
    }

    private static void testJsonRoundTrip() {
        Map<String, Object> value = Map.of("text", "سلام \\\"", "number", 12, "ok", true, "list", List.of("a", "b"));
        equal(value, Json.parse(Json.stringify(value)));
    }

    private static void testSignedUpdateManifest() throws Exception {
        String manifest = """
                {
                  "version": "9.9.9",
                  "url": "https://updates.perroute.invalid/PerRoute-Setup-9.9.9.exe",
                  "sha256": "190da6d7a920829612f400c8e48beec822eeec46c37d5f0185a3068bb8378095",
                  "size": 6644,
                  "signature": "1UhhKbomW9iYHY9J9pY/6Dnz30MAB4icgUf70x+t7YWoWChEidvtWGDMoYDYwlJYWEJ02pIoFxPvsg/obE8xAQ=="
                }
                """;
        SecureUpdateService.UpdateInfo update = new SecureUpdateService().parseAndVerify(
                manifest.getBytes(StandardCharsets.UTF_8));
        equal("9.9.9", update.version());
        check(update.newer(), "A newer signed version must be detected");
        check(SecureUpdateService.compareVersions("0.15.0", "0.14.2") > 0,
                "Semantic version comparison must identify newer releases");
        boolean rejected = false;
        try {
            new SecureUpdateService().parseAndVerify(
                    manifest.replace("9.9.9", "9.9.8").getBytes(StandardCharsets.UTF_8));
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, "A modified signed update manifest must be rejected");
    }

    private static void testTrafficHistory() throws Exception {
        Path file = Files.createTempDirectory("perroute-history-test").resolve("history.json");
        TrafficHistoryStore store = new TrafficHistoryStore(file);
        store.record("C:\\Apps\\Chrome.exe", 4096, 1024);
        store.record("C:\\Apps\\Chrome.exe", 2048, 512);
        TrafficHistoryStore.Snapshot snapshot = store.snapshot();
        equal(6144L, snapshot.todayDownloadBytes());
        equal(1536L, snapshot.todayUploadBytes());
        equal(1, snapshot.applications().size());
        store.close();
        TrafficHistoryStore restored = new TrafficHistoryStore(file);
        equal(6144L, restored.snapshot().monthDownloadBytes());
        restored.clear();
        equal(0L, restored.snapshot().todayDownloadBytes());
        restored.close();
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent());
    }

    private static void testQrImport() throws Exception {
        String link = "vless://11111111-1111-1111-1111-111111111111@qr.example:443?security=tls#QR";
        var matrix = new MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320);
        Path image = Files.createTempFile("perroute-qr-", ".png");
        ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "PNG", image.toFile());
        equal(link, new QrImportService().decode(image));
        Files.deleteIfExists(image);
    }

    private static void testVersionOneStateRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("perroute-state-v1-");
        AppStateStore store = new AppStateStore(directory.resolve("state.bin"));
        AppState state = new AppState();
        ProxyNode node = new ShareLinkParser().parse("trojan://secret@state.example:443?security=tls#State");
        RouteRule rule = new RouteRule("saved-rule", "Direct", "C:\\Apps\\Game.exe",
                RouteRule.MatchType.IP, "10.0.0.0/8", RouteRule.Action.DIRECT, true);
        ConnectionProfile profile = new ConnectionProfile("saved-profile", "Gaming",
                List.of("C:\\Apps\\Game.exe"), node.key(), ConnectionMode.NATIVE_PER_APP, List.of(rule));
        state.nodes().add(node); state.favoriteNodeKeys().add(node.key()); state.nodeTags().put(node.key(), "Gaming");
        state.routeRules().add(rule); state.profiles().add(profile); state.setActiveProfileId(profile.id());
        store.save(state);
        AppState restored = store.load();
        check(restored.favoriteNodeKeys().contains(node.key()), "Favorites must survive state encryption");
        equal("Gaming", restored.nodeTags().get(node.key()));
        equal(RouteRule.Action.DIRECT, restored.routeRules().get(0).action());
        equal("Gaming", restored.profiles().get(0).name());
        equal("saved-profile", restored.activeProfileId());
        try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(directory);
    }

    private static void testProxyHealthCheck() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = Thread.ofPlatform().start(() -> {
                try (Socket client = server.accept()) {
                    InputStream input = client.getInputStream();
                    input.readNBytes(3);
                    client.getOutputStream().write(new byte[]{0x05, 0x00});
                    byte[] request = input.readNBytes(5);
                    int domainLength = request[4] & 0xff;
                    input.readNBytes(domainLength + 2);
                    client.getOutputStream().write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                    client.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ProxyHealthCheck.Result result = new ProxyHealthCheck().check("127.0.0.1", server.getLocalPort());
            check(result.success(), "Health check must verify an outbound SOCKS tunnel");
            check(result.detail().startsWith("SOCKS tunnel reached"), "Health check must describe the tunnel result");
            responder.join(2_000);
        }
    }

    private static void testServerLatencyProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread accept = Thread.ofPlatform().start(() -> {
                try (Socket ignored = server.accept()) {
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ProxyNode node = new ProxyNode("latency", "Local", Protocol.VLESS, "127.0.0.1",
                    server.getLocalPort(), "id", Map.of(), "");
            ServerLatencyProbe.Result result = new ServerLatencyProbe().probe(node, 1_000);
            check(result.reachable(), "Latency probe must detect a reachable TCP endpoint");
            check(result.milliseconds() >= 1, "Latency probe must report milliseconds");
            accept.join(1_000);
        }
    }

    private static void testApplicationDiscoveryDeduplication() {
        var running = new ProcessDiscovery().list();
        long runningNames = running.stream().map(app -> app.name().toLowerCase()).distinct().count();
        equal((long)running.size(), runningNames);

        var installed = new InstalledApplicationDiscovery().list();
        long installedNames = installed.stream().map(app -> app.name().toLowerCase()).distinct().count();
        equal((long)installed.size(), installedNames);
        check(installed.stream().allMatch(app -> app.path().toLowerCase().endsWith(".exe")),
                "Installed application choices must point to executable files");
        check(installed.stream().map(app -> app.name().toLowerCase()).noneMatch(name ->
                        name.matches(".*\\b(jdk|sdk|runtime)\\b.*") || name.contains("development kit")),
                "Developer runtimes and SDKs must not appear as user applications");
    }

    private static void testLenientShareLinks() {
        ShareLinkParser parser = new ShareLinkParser();
        ProxyNode named = parser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&path=/a b|c&security=tls#DE | Frankfurt [01] 12.5GB");
        equal("example.com", named.address());
        equal(443, named.port());
        equal("DE | Frankfurt [01] 12.5GB", named.name());
        equal("/a b|c", named.option("path"));
        equal("11111111-1111-1111-1111-111111111111", named.credential());
        ProxyNode renamed = parser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&path=/a b|c&security=tls#DE | Frankfurt [01] 9.1GB");
        equal(named.identity(), renamed.identity());
        check(!named.key().equals(renamed.key()), "A renamed link gets a different raw key");
        ProxyNode stray = parser.parse("trojan://secret@host.example:8443?sni=a.example#100% uptime");
        equal("100% uptime", stray.name());
        equal(8443, stray.port());
        ProxyNode ipv6 = parser.parse("vless://11111111-1111-1111-1111-111111111111@[2001:db8::1]:443?security=tls#v6");
        equal("2001:db8::1", ipv6.address());
        ProxyNode sip002 = parser.parse("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pass".getBytes(StandardCharsets.UTF_8))
                + "@ss.example.com:8388/?plugin=obfs-local#SS | 1");
        equal("ss.example.com", sip002.address());
        equal(8388, sip002.port());
        equal("SS | 1", sip002.name());
        ParseResult mixed = new SubscriptionParser().parse(
                "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls#A | 1\n"
                + "vless://11111111-1111-1111-1111-111111111111@b.example:443?security=tls#B [2]\n");
        equal(2, mixed.nodes().size());
        equal(0, mixed.warnings().size());
    }

    private static void testRouteRuleEvaluator() {
        String browser = "C:\\Apps\\browser.exe";
        List<RouteRule> rules = List.of(
                new RouteRule("1", "Block ads", "", RouteRule.MatchType.DOMAIN, "ads.example.com, keyword:tracker", RouteRule.Action.BLOCK, true),
                new RouteRule("2", "Browser direct", browser, RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.DIRECT, true),
                new RouteRule("3", "Disabled", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.BLOCK, false),
                new RouteRule("4", "Office range", "", RouteRule.MatchType.IP, "203.0.113.0/24", RouteRule.Action.DIRECT, true),
                new RouteRule("5", "Mail ports", "", RouteRule.MatchType.PORT, "25,587,1000-2000", RouteRule.Action.BLOCK, true));
        List<String> apps = List.of(browser);

        var ads = RouteRuleEvaluator.evaluate(rules, apps, "", "sub.ads.example.com", 443);
        equal(RouteRule.Action.BLOCK, ads.action());
        equal("1", ads.rule().id());
        equal(RouteRule.Action.BLOCK, RouteRuleEvaluator.evaluate(rules, apps, "", "my-tracker.net", 443).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, browser, "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, List.of(), browser, "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "notexample.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "github.com", 443).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.113.77", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.114.1", 443).action());
        equal(RouteRule.Action.BLOCK, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.114.1", 1500).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, "", "192.168.1.10", 25).action());

        var unresolved = RouteRuleEvaluator.evaluate(rules, apps, "", "github.com", 443);
        check(!unresolved.certain(), "An unresolved host with an earlier IP rule must be reported as uncertain");
        equal("4", unresolved.uncertain().get(0).id());
        var geo = RouteRuleEvaluator.evaluate(List.of(
                new RouteRule("9", "Geo", "", RouteRule.MatchType.DOMAIN, "geosite:netflix", RouteRule.Action.DIRECT, true)),
                apps, "", "netflix.com", 443);
        check(!geo.certain() && geo.action() == RouteRule.Action.PROXY, "geosite matchers must not be guessed");
    }

    private static void testSpeedMeasurement() throws Exception {
        byte[] payload = new byte[300_000];
        var result = TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(payload), 1_000_000, 2_000_000_000L, 500_000_000L);
        check(result.success(), "A finished transfer must succeed");
        equal(300_000L, result.bytesReceived());
        check(result.bytesPerSecond() > 0, "A finished transfer must report a positive rate");
        var capped = TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(payload), 100_000, 2_000_000_000L, 0L);
        check(capped.bytesReceived() >= 100_000 && capped.bytesReceived() < 300_000, "The byte budget must stop the transfer");
        check(!TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(new byte[0]), 1_000, 1_000_000_000L, 0L).success(),
                "An empty response is a failed measurement");

        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".getBytes(StandardCharsets.US_ASCII);
        InputStream input = new java.io.ByteArrayInputStream(response);
        equal(200, TunnelHttp.readHeaders(input));
        equal("hi", new String(input.readAllBytes(), StandardCharsets.US_ASCII));
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
