package app.perroute.netstate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NetworkRestorePlan {
    public enum Action {
        RESTORE_PROXY,
        RESET_PROXY,
        DISCARD_PROXY_COPY,
        REMOVE_RECOVERY_ENTRY,
        STOP_CORES,
        RESTORE_ENGINE,
        STOP_LIMITED_ENGINE,
        CLEAR_SESSION,
        LEAVE
    }

    public record Step(Action action, String title, String detail, boolean needsAdmin, boolean releasesApps) {}

    private final List<Step> steps;
    private final String blocked;

    private NetworkRestorePlan(List<Step> steps, String blocked) {
        this.steps = List.copyOf(steps);
        this.blocked = blocked;
    }

    public List<Step> steps() { return steps; }
    public String blocked() { return blocked; }
    public List<Step> actions() { return steps.stream().filter(step -> step.action() != Action.LEAVE).toList(); }
    public List<Step> leftAlone() { return steps.stream().filter(step -> step.action() == Action.LEAVE).toList(); }
    public boolean needed() { return !actions().isEmpty(); }

    public static NetworkRestorePlan build(NetworkSnapshot current, NetworkSnapshot baseline, boolean connected) {
        if (connected) return new NetworkRestorePlan(List.of(), "Disconnect first. While connected these settings are in use and are restored when you disconnect.");
        Map<String, String> values = current.values();
        List<Step> steps = new ArrayList<>();
        String perRoute = NetworkStateKeys.perRouteServer();
        boolean proxyOurs = !perRoute.isEmpty() && perRoute.equals(current.value(NetworkStateKeys.PROXY_SERVER).trim());
        boolean backup = NetworkStateKeys.yes(current.value(NetworkStateKeys.PROXY_BACKUP));
        String server = current.value(NetworkStateKeys.PROXY_SERVER).trim();
        if (backup && (proxyOurs || server.isEmpty())) {
            steps.add(new Step(Action.RESTORE_PROXY, "Put the Windows proxy settings back",
                    "PerRoute saved your proxy settings before it changed them. They are put back exactly as they were.", false, false));
        } else if (proxyOurs) {
            String detail = baseline == null || NetworkStateKeys.perRouteProxy(baseline.values(), perRoute)
                    ? "PerRoute's proxy is still set, but its saved copy is missing. The proxy is switched off so apps connect directly."
                    : "PerRoute's proxy is still set, but its saved copy is missing. The settings from " + baseline.reason().label().toLowerCase() + " are put back.";
            steps.add(new Step(Action.RESET_PROXY, "Remove PerRoute's leftover proxy", detail, false, false));
        } else if (!server.isEmpty() || NetworkStateKeys.enabled(current.value(NetworkStateKeys.PROXY_ENABLE))) {
            steps.add(new Step(Action.LEAVE, "Windows proxy: left as it is",
                    "The proxy is set to " + (server.isEmpty() ? "a value" : server) + ", which PerRoute did not write. Another program or you set it, so it stays.", false, false));
            if (backup) steps.add(new Step(Action.DISCARD_PROXY_COPY, "Drop PerRoute's outdated saved copy",
                    "It is older than the current setting. It is deleted so it can never overwrite that setting; the setting itself is not changed.", false, false));
        }
        if (NetworkStateKeys.yes(current.value(NetworkStateKeys.RUN_ONCE))) {
            steps.add(new Step(Action.REMOVE_RECOVERY_ENTRY, "Remove the start-up recovery entry",
                    "It only exists to restore the proxy after a power loss and is not needed any more.", false, false));
        }
        String cores = current.value(NetworkStateKeys.CORE_PROCESSES).trim();
        if (!cores.isEmpty() && !"0".equals(cores)) {
            steps.add(new Step(Action.STOP_CORES, "Stop leftover connection cores",
                    cores + ("1".equals(cores) ? " Xray process is" : " Xray processes are") + " still running from an earlier session.", false, false));
        }
        boolean engineOurs = NetworkStateKeys.CONFIG_PERROUTE.equals(values.get(NetworkStateKeys.ENGINE_CONFIG));
        boolean engineRunning = NetworkStateKeys.RUNNING.equals(values.get(NetworkStateKeys.ENGINE_SERVICE));
        boolean engineBackup = NetworkStateKeys.yes(values.get(NetworkStateKeys.ENGINE_BACKUP));
        if (engineOurs && (engineRunning || engineBackup)) {
            steps.add(new Step(Action.RESTORE_ENGINE, "Stop per-app redirection and restore the engine's settings",
                    "The engine is still using PerRoute's app list" + (engineRunning ? " and redirecting those apps" : "") +
                            ". It is stopped and its previous settings are put back. Apps the kill switch was holding back connect directly again.",
                    true, engineRunning));
        } else if (NetworkStateKeys.CONFIG_OTHER.equals(values.get(NetworkStateKeys.ENGINE_CONFIG))) {
            steps.add(new Step(Action.LEAVE, "Per-app engine: left as it is",
                    "The engine is using an app list that PerRoute did not write, so it is not changed.", false, false));
        }
        if (NetworkStateKeys.RUNNING.equals(values.get(NetworkStateKeys.ENGINE_LIMITED))) {
            steps.add(new Step(Action.STOP_LIMITED_ENGINE, "Stop PerRoute's own copy of the engine",
                    "It redirects the selected apps while PerRoute is not connected.", false, true));
        }
        if (NetworkStateKeys.yes(current.value(NetworkStateKeys.SESSION))) {
            steps.add(new Step(Action.CLEAR_SESSION, "Clear the session marker",
                    "It says a connection was still open when PerRoute stopped.", false, false));
        }
        return new NetworkRestorePlan(steps, null);
    }
}
