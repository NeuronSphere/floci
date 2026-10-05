package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.common.docker.HostPathPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host directories bind-mounted into a cluster's k3s container, requested on CreateCluster with
 * reserved tags. Real EKS has no equivalent: this is an emulator-only way to let local workloads
 * read and write files that live on the developer's machine.
 *
 * <ul>
 *   <li>{@code floci:host-volume:<label>} = {@code /host/path:/container/path[:ro|:rw]}</li>
 *   <li>{@code floci:host-volumes} = a JSON array of the same specs</li>
 * </ul>
 *
 * <p>Like the other reserved creation tags, these are read once at CreateCluster, stored on the
 * cluster, and stripped from its public tags. Unlike cluster arguments, a malformed entry is
 * rejected rather than skipped: a mount that silently goes missing is worse than a refused
 * request. Host paths are checked separately, by {@link io.github.hectorvent.floci.core.common.docker.HostPathPolicy},
 * at creation and again before every container launch.
 */
public final class EksHostVolumes {

    static final String PREFIXED_KEY = ReservedTags.RESERVED_PREFIX + "host-volume:";
    static final String PLURAL_KEY = ReservedTags.RESERVED_PREFIX + "host-volumes";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final String READ_ONLY = "ro";
    private static final String READ_WRITE = "rw";
    private static final String HOST_PATH_SUBJECT = "Host volume host path";
    private static final String CONFIG_PREFIX = "floci.services.eks";

    /** One bind mount: an absolute host path, an absolute container path, and its access mode. */
    public record HostVolume(String hostPath, String containerPath, boolean readOnly) {

        String spec() {
            return hostPath + ":" + containerPath + (readOnly ? ":" + READ_ONLY : "");
        }
    }

    private EksHostVolumes() {
    }

    /**
     * Parses and validates the host-volume tags, returning normalized specs sorted by container
     * path so the stored value does not depend on tag order.
     *
     * @throws IllegalArgumentException naming the offending tag when a spec is malformed, targets a
     *     reserved container path, or reuses a container path
     */
    public static List<String> parse(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<HostVolume> volumes = new ArrayList<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (key.startsWith(PREFIXED_KEY)) {
                volumes.add(parseSpec(key, entry.getValue()));
            } else if (key.equals(PLURAL_KEY)) {
                for (String spec : parseList(key, entry.getValue())) {
                    volumes.add(parseSpec(key, spec));
                }
            }
        }
        volumes.sort(Comparator.comparing(HostVolume::containerPath));
        Set<String> containerPaths = new HashSet<>();
        List<String> specs = new ArrayList<>();
        for (HostVolume volume : volumes) {
            if (!containerPaths.add(volume.containerPath())) {
                throw new IllegalArgumentException("Host volume container path " + volume.containerPath()
                        + " is requested more than once.");
            }
            specs.add(volume.spec());
        }
        return specs;
    }

    /**
     * Checks every host path against the shared host-path policy and this service's allowlist.
     * Runs at CreateCluster and again before each container launch, so a root removed from the
     * configuration after creation stops the mount instead of being honoured from storage.
     */
    public static void validateHostPaths(List<String> specs, HostPathPolicy policy, EmulatorConfig config) {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        for (String spec : specs) {
            policy.validate(toVolume(spec).hostPath(), HOST_PATH_SUBJECT, CONFIG_PREFIX, () -> {
                EmulatorConfig.EksServiceConfig eksConfig = config.services().eks();
                return new HostPathPolicy.Allowlist(eksConfig.hostVolumeRoots().orElse(List.of()),
                        eksConfig.allowUnsafeHostVolumes());
            });
        }
    }

    /** Reads back a spec produced by {@link #parse(Map)}. */
    public static HostVolume toVolume(String spec) {
        return parseSpec("hostVolumes", spec);
    }

    private static List<String> parseList(String key, String value) {
        try {
            List<String> specs = MAPPER.readValue(value, STRING_LIST_TYPE);
            return specs != null ? specs : List.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("Tag " + key + " must be a JSON array of host volume specs: "
                    + e.getMessage());
        }
    }

    private static HostVolume parseSpec(String key, String spec) {
        if (spec == null || spec.isBlank()) {
            throw invalid(key, spec, "a spec of the form /host/path:/container/path[:ro] is required");
        }
        for (int i = 0; i < spec.length(); i++) {
            if (Character.isISOControl(spec.charAt(i))) {
                throw invalid(key, spec, "it contains control characters");
            }
        }
        String[] parts = spec.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw invalid(key, spec, "expected /host/path:/container/path[:ro]");
        }
        boolean readOnly = false;
        if (parts.length == 3) {
            if (READ_ONLY.equals(parts[2])) {
                readOnly = true;
            } else if (!READ_WRITE.equals(parts[2])) {
                throw invalid(key, spec, "the access mode must be ro or rw");
            }
        }
        String hostPath = parts[0];
        if (hostPath.isEmpty()) {
            throw invalid(key, spec, "the host path is empty");
        }
        String containerPath = validateContainerPath(key, spec, parts[1]);
        return new HostVolume(hostPath, containerPath, readOnly);
    }

    private static String validateContainerPath(String key, String spec, String containerPath) {
        Path path;
        try {
            path = Path.of(containerPath);
        } catch (InvalidPathException e) {
            throw invalid(key, spec, "the container path is not a valid path");
        }
        if (containerPath.isEmpty() || !containerPath.startsWith("/")) {
            throw invalid(key, spec, "the container path must be absolute");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw invalid(key, spec, "the container path must not contain '..' segments");
            }
        }
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0) {
            throw invalid(key, spec, "the container path must not be the filesystem root");
        }
        Path k3sData = Path.of(EksClusterManager.K3S_DATA_DIR);
        if (normalized.startsWith(k3sData) || k3sData.startsWith(normalized)) {
            throw invalid(key, spec, "the container path overlaps the k3s data directory "
                    + EksClusterManager.K3S_DATA_DIR);
        }
        return normalized.toString();
    }

    private static IllegalArgumentException invalid(String key, String spec, String reason) {
        return new IllegalArgumentException("Invalid host volume " + key + "=" + spec + ": " + reason + ".");
    }
}
