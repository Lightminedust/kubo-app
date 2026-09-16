package com.kubo.model.docker;

import java.util.Objects;

/**
 * A Docker image on disk.
 *
 * @param dangling true for an untagged leftover ({@code <none>:<none>}), safe to prune
 */
public record DockerImage(String id, String repository, String tag, long sizeBytes, String createdSince,
                          boolean dangling) {

    public DockerImage {
        Objects.requireNonNull(id, "id");
        repository = repository == null ? "" : repository;
        tag = tag == null ? "" : tag;
        createdSince = createdSince == null ? "" : createdSince;
    }

    public String reference() {
        return dangling ? "<none>" : repository + ":" + tag;
    }

    public String shortId() {
        String bare = id.startsWith("sha256:") ? id.substring(7) : id;
        return bare.length() > 12 ? bare.substring(0, 12) : bare;
    }
}
