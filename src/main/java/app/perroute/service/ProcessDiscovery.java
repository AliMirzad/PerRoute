package app.perroute.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ProcessDiscovery {
    public List<RunningApplication> list() {
        Map<String, RunningApplication> applications = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ProcessHandle.allProcesses()
                .map(ProcessHandle::info)
                .map(ProcessHandle.Info::command)
                .flatMap(java.util.Optional::stream)
                .map(Path::of)
                .filter(Files::isRegularFile)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(path -> new RunningApplication(path.getFileName().toString(), path.toString()))
                .forEach(app -> applications.putIfAbsent(app.name(), app));
        return applications.values().stream()
                .sorted(Comparator.comparing(RunningApplication::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public record RunningApplication(String name, String path) {
        @Override public String toString() { return name + "  —  " + path; }
    }
}
