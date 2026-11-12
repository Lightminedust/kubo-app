package com.kubo.update;

import com.kubo.platform.Host;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public enum PackageKind {
    EXE("exe", "Windows installer (EXE)", Host.Kind.WINDOWS),
    MSI("msi", "Windows installer (MSI)", Host.Kind.WINDOWS),
    ZIP("portable-zip", "Windows portable (ZIP)", Host.Kind.WINDOWS),
    DEB("deb", "Linux package (DEB)", Host.Kind.LINUX),
    RPM("rpm", "Linux package (RPM)", Host.Kind.LINUX),
    TAR("portable-tar", "Linux portable (tar.gz)", Host.Kind.LINUX),
    UNKNOWN("unknown", "Unpackaged build", Host.Kind.OTHER);

    private final String id, label;
    private final Host.Kind system;
    PackageKind(String id, String label, Host.Kind system) { this.id = id; this.label = label; this.system = system; }
    public String id() { return id; }
    public String label() { return label; }
    public Host.Kind system() { return system; }
    public boolean portable() { return this == ZIP || this == TAR; }
    public static PackageKind of(String id) {
        return Arrays.stream(values()).filter(kind -> kind.id.equals(id)).findFirst().orElse(UNKNOWN);
    }

    public boolean matches(String name, Version version, String architecture) {
        // These are the six x64 artifacts published by our packaging workflow. Never substitute an
        // installer for a portable edition, or x64 for an unsupported architecture.
        if (!architecture.equals("x86_64")) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return switch (this) {
            case EXE -> value.equals("kubo-setup.exe");
            case MSI -> value.equals("kubo-" + version + ".msi");
            case ZIP -> value.equals("kubo-windows-portable.zip");
            case DEB -> value.equals("kubo_" + version + "_amd64.deb");
            case RPM -> value.matches("kubo-" + Pattern.quote(version.toString()) + "-[0-9]+\\.x86_64\\.rpm");
            case TAR -> value.equals("kubo-linux-x86_64.tar.gz");
            case UNKNOWN -> false;
        };
    }
}
