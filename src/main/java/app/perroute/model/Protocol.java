package app.perroute.model;

public enum Protocol {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    XRAY_PROFILE("Xray Profile");

    private final String label;

    Protocol(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
