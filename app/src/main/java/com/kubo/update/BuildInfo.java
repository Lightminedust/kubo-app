package com.kubo.update;

import com.kubo.platform.Host;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public record BuildInfo(Version version, PackageKind kind, String architecture, Path launcher) {
    public static BuildInfo current() {
        Properties properties = new Properties();
        try (var resource = BuildInfo.class.getResourceAsStream("/com/kubo/update/build.properties")) {
            if (resource != null) properties.load(resource);
        } catch (IOException e) { throw new IllegalStateException("Cannot read Kubo's build version", e); }
        Version version = Version.parse(properties.getProperty("version")).orElseThrow(
                () -> new IllegalStateException("Kubo's build version is missing"));
        PackageKind kind = PackageKind.of(System.getProperty("kubo.package", "unknown"));
        if (kind.system() != Host.kind()) kind = PackageKind.UNKNOWN;
        String executable = System.getProperty("jpackage.app-path", "");
        return new BuildInfo(version, kind, architecture(System.getProperty("os.arch", "")),
                executable.isBlank() ? null : Path.of(executable).toAbsolutePath().normalize());
    }

    public static String architecture(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "amd64", "x64", "x86_64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    public String description() { return kind.label() + " · " + architecture; }
}
