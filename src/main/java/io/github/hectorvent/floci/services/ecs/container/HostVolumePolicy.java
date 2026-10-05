package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.HostPathPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Function;

/**
 * Guards task-definition {@code volumes[].host.sourcePath} against the unsafe bind mounts a
 * caller-controlled task definition could otherwise request: relative/traversal paths, the bare
 * filesystem root, anything that reaches the Docker daemon socket, and (unless
 * {@code floci.services.ecs.allow-unsafe-host-volumes} is set) paths outside the configured
 * {@code floci.services.ecs.host-volume-roots} allowlist. The checks themselves live in
 * {@link HostPathPolicy}, which EKS cluster host volumes share.
 *
 * <p>Shared between {@link io.github.hectorvent.floci.services.ecs.EcsJsonHandler}, which runs
 * this check once at RegisterTaskDefinition time, and {@link EcsContainerManager}, which runs it
 * again immediately before every bind mount at RunTask time. The second check narrows the
 * TOCTOU window between validation and mount (a symlink swapped in after registration) and also
 * covers task definitions that were persisted before this policy existed.
 */
@ApplicationScoped
public class HostVolumePolicy {

    private static final String SUBJECT = "volumes[].host.sourcePath";
    private static final String CONFIG_PREFIX = "floci.services.ecs";

    private final EmulatorConfig config;
    private final HostPathPolicy policy;

    @Inject
    public HostVolumePolicy(EmulatorConfig config) {
        this(config, System::getenv);
    }

    /**
     * @param environment lookup for the {@code DOCKER_HOST}, {@code DOCKER_CONFIG} and
     *     {@code DOCKER_CONTEXT} variables; {@code System::getenv} in production
     */
    public HostVolumePolicy(EmulatorConfig config, Function<String, String> environment) {
        this.config = config;
        this.policy = new HostPathPolicy(config, environment);
    }

    public void validate(String sourcePath) {
        policy.validate(sourcePath, SUBJECT, CONFIG_PREFIX, () -> {
            EmulatorConfig.EcsServiceConfig ecsConfig = config.services().ecs();
            List<String> roots = ecsConfig.hostVolumeRoots().orElse(List.of());
            return new HostPathPolicy.Allowlist(roots, ecsConfig.allowUnsafeHostVolumes());
        });
    }
}
