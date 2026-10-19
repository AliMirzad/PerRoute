package app.perroute.netstate;

import java.util.List;
import java.util.Map;

public final class NetworkStateKeys {
    private NetworkStateKeys() {}

    public static final String PROXY_ENABLE = "wininet.ProxyEnable";
    public static final String PROXY_SERVER = "wininet.ProxyServer";
    public static final String PROXY_OVERRIDE = "wininet.ProxyOverride";
    public static final String PROXY_PAC = "wininet.AutoConfigURL";
    public static final String PROXY_BACKUP = "wininet.backupPending";
    public static final String RUN_ONCE = "startup.recoveryEntry";
    public static final String ENGINE_INSTALLED = "engine.installed";
    public static final String ENGINE_SERVICE = "engine.service";
    public static final String ENGINE_CONFIG = "engine.config";
    public static final String ENGINE_APPS = "engine.configApps";
    public static final String ENGINE_BACKUP = "engine.backupPending";
    public static final String ENGINE_LIMITED = "engine.limited";
    public static final String CORE_PROCESSES = "core.processes";
    public static final String SESSION = "perroute.session";
    public static final String WINHTTP = "context.winhttpProxy";
    public static final String DNS = "context.dnsServers";
    public static final String GATEWAY = "context.gateways";
    public static final String ADAPTERS = "context.adapters";

    public static final String CONFIG_PERROUTE = "perroute", CONFIG_OTHER = "other", CONFIG_NONE = "none";
    public static final String RUNNING = "running", STOPPED = "stopped", MISSING = "not installed";
    public static final String PERROUTE_OVERRIDE = "<local>;localhost;127.*;[::1]";

    public enum Owner {
        PERROUTE("Set by PerRoute"),
        OTHER("Set by Windows or another program"),
        NONE("Not set"),
        CONTEXT("For reference only");

        private final String label;
        Owner(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Key(String id, String group, String label, String explanation, boolean managed) {}

    public static final List<Key> ALL = List.of(
            new Key(PROXY_ENABLE, "Windows proxy", "Proxy switch", "Whether apps that follow Windows proxy settings use a proxy.", true),
            new Key(PROXY_SERVER, "Windows proxy", "Proxy server", "Where those apps send their traffic.", true),
            new Key(PROXY_OVERRIDE, "Windows proxy", "Bypass list", "Addresses that skip the proxy.", true),
            new Key(PROXY_PAC, "Windows proxy", "Setup script", "An automatic proxy configuration script.", true),
            new Key(PROXY_BACKUP, "Windows proxy", "Saved original", "PerRoute's copy of the settings it replaced, kept until they are put back.", true),
            new Key(RUN_ONCE, "Start-up", "Recovery entry", "Restores the proxy at the next sign-in after a power loss.", true),
            new Key(ENGINE_INSTALLED, "Per-app engine", "Installed", "The per-app redirection engine (ProxiFyre).", false),
            new Key(ENGINE_SERVICE, "Per-app engine", "Service", "Whether the engine is redirecting apps right now.", true),
            new Key(ENGINE_CONFIG, "Per-app engine", "App list", "Whose list of apps the engine is using.", true),
            new Key(ENGINE_APPS, "Per-app engine", "Apps redirected", "How many apps the engine redirects.", true),
            new Key(ENGINE_BACKUP, "Per-app engine", "Saved original", "PerRoute's copy of the engine settings it replaced.", true),
            new Key(ENGINE_LIMITED, "Per-app engine", "Engine without Administrator", "PerRoute's own copy of the engine, used when it is not elevated.", true),
            new Key(CORE_PROCESSES, "Connection core", "Running cores", "Xray processes started by PerRoute.", true),
            new Key(SESSION, "Connection core", "Session marker", "Present while PerRoute is connected; left behind after a crash.", true),
            new Key(WINHTTP, "Context", "WinHTTP proxy", "The proxy of Windows services. PerRoute never changes it.", false),
            new Key(DNS, "Context", "DNS servers", "The resolvers Windows uses. PerRoute never changes them.", false),
            new Key(GATEWAY, "Context", "Default gateways", "Where your network leads. PerRoute never changes them.", false),
            new Key(ADAPTERS, "Context", "Network adapters", "The connected adapters and their addresses.", false)
    );

    public static Key key(String id) {
        for (Key key : ALL) if (key.id().equals(id)) return key;
        throw new IllegalArgumentException("Unknown network reading " + id);
    }

    public static boolean perRouteProxy(Map<String, String> values, String perRouteServer) {
        return enabled(values.getOrDefault(PROXY_ENABLE, "")) && perRouteServer.equals(values.getOrDefault(PROXY_SERVER, "").trim());
    }

    public static boolean enabled(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return text.equals("0x1") || text.equals("1");
    }

    private static volatile String perRouteServer = "";

    public static void setPerRouteServer(String value) { perRouteServer = value == null ? "" : value; }

    public static String perRouteServer() { return perRouteServer; }

    static Owner owner(Key key, String value, Map<String, String> values) {
        if (!key.managed() && !key.id().equals(ENGINE_INSTALLED)) return Owner.CONTEXT;
        String text = value == null ? "" : value.trim();
        boolean ours = !perRouteServer.isEmpty() && perRouteProxy(values, perRouteServer);
        return switch (key.id()) {
            case PROXY_ENABLE -> enabled(text) ? (ours ? Owner.PERROUTE : Owner.OTHER) : Owner.NONE;
            case PROXY_SERVER -> text.isEmpty() ? Owner.NONE : text.equals(perRouteServer) ? Owner.PERROUTE : Owner.OTHER;
            case PROXY_OVERRIDE -> text.isEmpty() ? Owner.NONE : (ours && text.equals(PERROUTE_OVERRIDE)) ? Owner.PERROUTE : Owner.OTHER;
            case PROXY_PAC -> text.isEmpty() ? Owner.NONE : Owner.OTHER;
            case PROXY_BACKUP, RUN_ONCE, ENGINE_BACKUP, SESSION -> yes(text) ? Owner.PERROUTE : Owner.NONE;
            case ENGINE_INSTALLED -> Owner.CONTEXT;
            case ENGINE_SERVICE -> RUNNING.equals(text)
                    ? (CONFIG_PERROUTE.equals(values.get(ENGINE_CONFIG)) ? Owner.PERROUTE : Owner.OTHER) : Owner.NONE;
            case ENGINE_CONFIG -> CONFIG_PERROUTE.equals(text) ? Owner.PERROUTE : CONFIG_OTHER.equals(text) ? Owner.OTHER : Owner.NONE;
            case ENGINE_APPS -> CONFIG_PERROUTE.equals(values.get(ENGINE_CONFIG)) && !"0".equals(text) ? Owner.PERROUTE : Owner.NONE;
            case ENGINE_LIMITED -> RUNNING.equals(text) ? Owner.PERROUTE : Owner.NONE;
            case CORE_PROCESSES -> !text.isEmpty() && !"0".equals(text) ? Owner.PERROUTE : Owner.NONE;
            default -> Owner.CONTEXT;
        };
    }

    public static boolean yes(String value) {
        return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "present".equalsIgnoreCase(value);
    }

    public static String display(String id, String value) {
        String text = value == null ? "" : value.trim();
        return switch (id) {
            case PROXY_ENABLE -> enabled(text) ? "On" : "Off";
            case PROXY_SERVER, PROXY_OVERRIDE, PROXY_PAC -> text.isEmpty() ? "Not set" : text;
            case PROXY_BACKUP, ENGINE_BACKUP -> yes(text) ? "Waiting to be put back" : "None";
            case RUN_ONCE -> yes(text) ? "Registered" : "Not registered";
            case SESSION -> yes(text) ? "Present" : "None";
            case ENGINE_INSTALLED -> yes(text) ? "Yes" : "No";
            case ENGINE_SERVICE, ENGINE_LIMITED -> text.isEmpty() ? "Unknown" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
            case ENGINE_CONFIG -> switch (text) {
                case CONFIG_PERROUTE -> "PerRoute's app list";
                case CONFIG_OTHER -> "Another app list";
                default -> "None";
            };
            case ENGINE_APPS -> text.isEmpty() || "0".equals(text) ? "None" : text + ("1".equals(text) ? " app" : " apps");
            case CORE_PROCESSES -> text.isEmpty() || "0".equals(text) ? "None" : text + " running";
            default -> text.isEmpty() ? "None" : text;
        };
    }
}
