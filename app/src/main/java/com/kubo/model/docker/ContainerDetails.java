package com.kubo.model.docker;

import java.util.List;

/**
 * The fuller picture of one container, read on demand with {@code docker inspect}.
 *
 * @param command       the entrypoint and command it runs
 * @param restartPolicy how Docker restarts it ("no", "always", "unless-stopped"…)
 * @param created       when it was created, as Docker prints it
 * @param env           its environment variables, secrets flagged for masking
 */
public record ContainerDetails(String id, String name, String image, ContainerState state, String command,
                               String restartPolicy, String created, List<PortMapping> ports, List<Mount> mounts,
                               List<EnvVar> env) {

    public ContainerDetails {
        ports = List.copyOf(ports == null ? List.of() : ports);
        mounts = List.copyOf(mounts == null ? List.of() : mounts);
        env = List.copyOf(env == null ? List.of() : env);
    }

    public int envCount() {
        return env.size();
    }
}
