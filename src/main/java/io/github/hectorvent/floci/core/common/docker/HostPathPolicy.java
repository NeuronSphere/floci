package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Guards a caller-supplied host path that Floci is about to bind-mount into a container it
 * launches: relative/traversal paths, the bare filesystem root, anything that reaches the Docker
 * daemon socket, and (unless the service's {@code allow-unsafe-host-volumes} is set) paths
 * outside the service's {@code host-volume-roots} allowlist.
 *
 * <p>Traversal segments, the bare root, and the Docker socket (or any ancestor directory that
 * contains it, e.g. {@code /var/run}) are always rejected, even with the unsafe escape hatch
 * enabled, since no legitimate caller needs them.
 *
 * <p>Each service names the request field its error messages refer to, its configuration prefix,
 * and supplies its {@link Allowlist}. The allowlist is read only after the always-on checks pass.
 * ECS task-definition host volumes and EKS cluster host volumes share this policy.
 */
public class HostPathPolicy {

    private static final List<String> LITERAL_DOCKER_SOCKET_PATHS = List.of(
            "/var/run/docker.sock",
            "/run/docker.sock");

    /**
     * @param roots the approved parent directories; empty rejects every path unless {@code allowUnsafe}
     * @param allowUnsafe bypasses the allowlist (never the always-on checks)
     */
    public record Allowlist(List<String> roots, boolean allowUnsafe) {
    }

    private final EmulatorConfig config;
    private final Function<String, String> environment;
    // Resolved once on first use: the environment and Docker context files do not change for
    // the life of the process, and the resolver logs when it picks a context endpoint.
    private volatile String effectiveDockerHost;

    /**
     * @param environment lookup for the {@code DOCKER_HOST}, {@code DOCKER_CONFIG} and
     *     {@code DOCKER_CONTEXT} variables; {@code System::getenv} in production
     */
    public HostPathPolicy(EmulatorConfig config, Function<String, String> environment) {
        this.config = config;
        this.environment = environment;
    }

    /**
     * @param subject the request field named in error messages, e.g. {@code volumes[].host.sourcePath}
     * @param configPrefix the service's configuration prefix, e.g. {@code floci.services.ecs}
     * @param allowlist the service's allowlist settings, read only after the always-on checks pass
     */
    public void validate(String sourcePath, String subject, String configPrefix, Supplier<Allowlist> allowlist) {
        Path rawPath;
        try {
            rawPath = Path.of(sourcePath);
        } catch (InvalidPathException e) {
            throw new AwsException("InvalidParameterException",
                    subject + " is not a valid filesystem path: " + sourcePath, 400);
        }
        if (!rawPath.isAbsolute()) {
            throw new AwsException("InvalidParameterException",
                    subject + " must be an absolute path: " + sourcePath, 400);
        }
        for (Path segment : rawPath) {
            if ("..".equals(segment.toString())) {
                throw new AwsException("InvalidParameterException",
                        subject + " must not contain '..' segments: " + sourcePath, 400);
            }
        }
        Path normalized = rawPath.normalize();
        if (normalized.getNameCount() == 0) {
            throw new AwsException("InvalidParameterException",
                    subject + " must not be the filesystem root: " + sourcePath, 400);
        }

        Path canonical = canonicalizeForContainment(normalized);
        if (exposesDockerSocket(normalized, canonical)) {
            throw new AwsException("InvalidParameterException",
                    subject + " must not target or contain the Docker socket: " + sourcePath, 400);
        }

        Allowlist settings = allowlist.get();
        if (settings.allowUnsafe()) {
            return;
        }
        String rootsKey = configPrefix + ".host-volume-roots";
        String unsafeKey = configPrefix + ".allow-unsafe-host-volumes";
        List<String> roots = settings.roots() == null ? List.of() : settings.roots();
        if (roots.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    subject + " is rejected by default: " + sourcePath
                            + ". Configure " + rootsKey + " (" + environmentName(rootsKey) + ")"
                            + " with approved parent directories, or set " + unsafeKey
                            + " (" + environmentName(unsafeKey) + ") to true to allow"
                            + " any host path.", 400);
        }
        for (String root : roots) {
            Path rootCanonical = canonicalizeForContainment(Path.of(root).normalize());
            if (canonical.equals(rootCanonical) || canonical.startsWith(rootCanonical)) {
                return;
            }
        }
        throw new AwsException("InvalidParameterException",
                subject + " is not within an approved host-volume root: " + sourcePath, 400);
    }

    private static String environmentName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /**
     * True when {@code normalized} (or its canonical form) is the Docker socket itself, or is an
     * ancestor directory that contains it (e.g. {@code /var/run}, {@code /run}, or {@code /var}
     * all expose {@code docker.sock} just as directly as naming the socket file outright).
     */
    private boolean exposesDockerSocket(Path normalized, Path canonical) {
        for (String candidate : dockerSocketCandidates()) {
            Path candidateNormalized;
            try {
                candidateNormalized = Path.of(candidate).normalize();
            } catch (InvalidPathException e) {
                continue;
            }
            if (normalized.equals(candidateNormalized)) {
                return true;
            }
            Path candidateCanonical = canonicalizeCandidateForContainment(candidateNormalized);
            if (candidateCanonical.equals(canonical) || candidateCanonical.startsWith(canonical)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Candidate Docker socket paths: the socket Floci's own Docker client connects to, plus the
     * two conventional Linux/macOS locations and Docker Desktop's per-user rootless socket as
     * defence in depth. The first comes from the same resolution {@link DockerClientProducer}
     * uses to build the client ({@code floci.docker.docker-host}, then {@code DOCKER_HOST}, then
     * the active Docker context), so a daemon reached through, say, {@code DOCKER_HOST=unix:///tmp/x.sock}
     * or a Colima context is protected too. Only a {@code unix://} endpoint names a filesystem
     * path; {@code tcp://} and Windows named pipes have nothing a bind mount could expose.
     */
    private List<String> dockerSocketCandidates() {
        List<String> candidates = new ArrayList<>(LITERAL_DOCKER_SOCKET_PATHS);
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(userHome + "/.docker/run/docker.sock");
        }
        String daemonHost = effectiveDockerHost();
        if (daemonHost.startsWith("unix://")) {
            String path = daemonHost.substring("unix://".length());
            if (!path.isBlank()) {
                candidates.add(path);
            }
        }
        return candidates;
    }

    private String effectiveDockerHost() {
        String host = effectiveDockerHost;
        if (host == null) {
            String resolved = DockerClientProducer.resolveDockerConnection(config.docker(), environment).host();
            host = resolved == null ? "" : resolved;
            effectiveDockerHost = host;
        }
        return host;
    }

    /**
     * Canonicalizes a Docker-socket candidate by resolving symlinks in its parent directory only,
     * then re-appending the literal socket file name. Docker Desktop for Mac makes
     * {@code /var/run/docker.sock} itself a symlink to a per-user path (e.g. under
     * {@code ~/.docker/run/}); fully resolving the candidate would follow that symlink and land
     * outside {@code /var/run}, which would defeat the ancestor check for exactly the case it
     * exists to catch (a caller mounting {@code /var/run} or {@code /run} wholesale). Resolving
     * only the parent still catches a symlinked ancestor directory, while leaving the socket's own
     * leaf symlink (if any) out of the comparison, since containment is about what a bind mount of
     * the caller's path would literally expose, not where the socket's target ultimately lives.
     */
    private static Path canonicalizeCandidateForContainment(Path normalizedAbsolutePath) {
        Path parent = normalizedAbsolutePath.getParent();
        Path fileName = normalizedAbsolutePath.getFileName();
        if (parent == null || fileName == null) {
            return canonicalizeForContainment(normalizedAbsolutePath);
        }
        return canonicalizeForContainment(parent).resolve(fileName);
    }

    /**
     * Resolves symlinks on the nearest existing ancestor of {@code normalizedAbsolutePath} (via
     * {@link Path#toRealPath}) and re-appends the remaining, not-yet-existing path segments. This
     * catches a symlinked parent directory that escapes an approved root, or that resolves onto
     * the Docker socket, even when the leaf itself does not exist yet (as with a host volume
     * Docker will create on first use).
     */
    private static Path canonicalizeForContainment(Path normalizedAbsolutePath) {
        Path candidate = normalizedAbsolutePath;
        Deque<String> unresolvedSuffix = new ArrayDeque<>();
        while (candidate != null) {
            try {
                Path real = candidate.toRealPath();
                for (String segment : unresolvedSuffix) {
                    real = real.resolve(segment);
                }
                return real;
            } catch (IOException e) {
                Path fileName = candidate.getFileName();
                if (fileName != null) {
                    unresolvedSuffix.addFirst(fileName.toString());
                }
                candidate = candidate.getParent();
            }
        }
        return normalizedAbsolutePath;
    }
}
