package app.perroute.backend.api;

import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.service.XrayConfigGenerator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Machine operations used by the desktop controller. Implementations own platform-specific effects. */
public interface ConnectionBackend {
    boolean coreReady();
    boolean perAppReady();
    boolean elevated();

    /** Reject an invalid configuration without changing the running core. */
    void validate(String config, boolean tun) throws Exception;
    void start(String config, boolean tun) throws Exception;
    void stop() throws Exception;
    boolean isRunning();
    void stopOrphan() throws Exception;
    void addLogListener(Consumer<String> listener);
    void addRunningListener(Consumer<Boolean> listener);

    void applyPerApp(List<String> applications) throws Exception;
    void restorePerApp() throws Exception;
    void stopPerAppSidecar() throws Exception;
    void applySystemProxy() throws Exception;
    void restoreSystemProxy() throws Exception;

    TunnelStatus checkTunnel(int socksPort);
    Map<String, Long> readInboundCounters() throws Exception;
    /** A null port observes the direct route rather than a loopback SOCKS inbound. */
    RouteObservation lookupPublicAddress(Integer socksPort);
    List<String> systemDnsServers();
    String publicResolver();
    List<ApplicationDescriptor> installedApplications();
    List<ApplicationDescriptor> runningApplications();

    /** Replace one live route; unsupported implementations must throw so the controller can restart safely. */
    void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) throws Exception;
    /** Bind all gateway listeners atomically or throw without leaving a partial gateway running. */
    void startGateway(List<AppGateway.Route> routes) throws Exception;
    void stopGateway();
    void setGatewayLimits(String applicationPath, long downloadBytesPerSecond, long uploadBytesPerSecond);
    Map<String, AppGateway.Counters> gatewayCounters();
    boolean gatewayRunning();
    void addGatewayListener(Consumer<GatewayConnection> listener);

    void installCore(Consumer<String> progress) throws Exception;
    void installPerApp(Consumer<String> progress) throws Exception;
    UpdateDescriptor checkForUpdate(String feed) throws Exception;
    Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) throws Exception;
    void launchUpdate(Path installer) throws Exception;
}
