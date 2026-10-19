package app.perroute.bridge;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class BridgeLinks {
    private BridgeLinks() {}

    public static String socks(String host, int port, BridgeDevice device) {
        String credentials = Base64.getEncoder().encodeToString((device.user() + ":" + device.password()).getBytes(StandardCharsets.UTF_8));
        String remark = URLEncoder.encode("PerRoute " + device.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return "socks://" + credentials + "@" + host + ":" + port + "#" + remark;
    }

    public static String http(String host, int port, BridgeDevice device) {
        return "http://" + encode(device.user()) + ":" + encode(device.password()) + "@" + host + ":" + port;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
