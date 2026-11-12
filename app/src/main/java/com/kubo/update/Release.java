package com.kubo.update;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public record Release(Version version, String tag, List<Asset> assets) {
    public static final String REPOSITORY = "https://github.com/Lightminedust/Kubo";
    public record Asset(String name, URI url, long size, String sha256) {
        public Asset {
            if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.equals(".") || name.equals("..")
                    || url == null || !ReleaseClient.allowed(url) || size <= 0 || size > ReleaseClient.MAX_DOWNLOAD
                    || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid release asset");
        }
    }
    public URI page() { return URI.create(REPOSITORY + "/releases/tag/" + tag); }
    public Optional<Asset> assetFor(BuildInfo build) {
        List<Asset> matches = assets.stream().filter(asset ->
                build.kind().matches(asset.name(), version, build.architecture())).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }
}
