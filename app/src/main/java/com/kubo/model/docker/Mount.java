package com.kubo.model.docker;

import java.util.Objects;

/**
 * A folder or volume mounted into a container.
 *
 * @param type        "bind" for a host folder, "volume" for a named Docker volume, etc.
 * @param source      the host folder or the volume name
 * @param destination where it appears inside the container
 * @param readWrite   true when the container may write to it
 */
public record Mount(String type, String source, String destination, boolean readWrite) {

    public Mount {
        type = type == null || type.isBlank() ? "volume" : type;
        Objects.requireNonNull(destination, "destination");
        source = source == null ? "" : source;
    }

    public boolean isBind() {
        return type.equalsIgnoreCase("bind");
    }
}
