package com.kubo.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseTest {
    static final Version VERSION = new Version(1, 10, 0);
    static final String HASH = "a".repeat(64);
    static final List<String> NAMES = List.of("Kubo-Setup.exe", "Kubo-1.10.0.msi", "Kubo-windows-portable.zip",
            "kubo_1.10.0_amd64.deb", "kubo-1.10.0-1.x86_64.rpm", "Kubo-linux-x86_64.tar.gz");
    static String json(String tag, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"assets\":[" + assets + "]}";
    }
    static String assetJson(String name) {
        return "{\"name\":\"" + name + "\",\"size\":123,\"digest\":\"sha256:" + HASH
                + "\",\"browser_download_url\":\"" + Release.REPOSITORY + "/releases/download/v1.10.0/" + name + "\"}";
    }
    static java.util.Optional<Release> parse(String value) throws Exception { return ReleaseClient.parse(value.getBytes(StandardCharsets.UTF_8)); }

    @Test void comparesNumbersInsteadOfText() {
        assertTrue(Version.parse("v1.10.0").orElseThrow().compareTo(Version.parse("1.9.12").orElseThrow()) > 0);
        assertEquals(0, VERSION.compareTo(Version.parse("1.10.0").orElseThrow()));
        assertTrue(VERSION.compareTo(new Version(2, 0, 0)) < 0);
        assertTrue(VERSION.compareTo(new Version(1, 10, 1)) < 0);
    }
    @ParameterizedTest @ValueSource(strings={"1.0", "v1.0.0-beta", "1.0.0+build", "01.0.0", "v1.999999999999.0", "../../2.0.0", "1.0.0\n", ""})
    void rejectsAmbiguousOrUnstableVersions(String value) { assertTrue(Version.parse(value).isEmpty()); }

    @ParameterizedTest @CsvSource({"EXE,0", "MSI,1", "ZIP,2", "DEB,3", "RPM,4", "TAR,5"})
    void selectsExactlyTheInstalledFormat(PackageKind kind, int index) throws Exception {
        Release release = parse(json("v1.10.0", String.join(",", NAMES.stream().map(ReleaseTest::assetJson).toList()))).orElseThrow();
        var build = new BuildInfo(new Version(1, 9, 0), kind, "x86_64", null);
        assertEquals(NAMES.get(index), release.assetFor(build).orElseThrow().name());
        assertTrue(release.assetFor(new BuildInfo(build.version(), kind, "aarch64", null)).isEmpty());
    }
    @Test void doesNotSwitchFromPortableToInstallerWhenZipIsMissing() throws Exception {
        Release release = parse(json("v1.10.0", assetJson(NAMES.getFirst()))).orElseThrow();
        assertTrue(release.assetFor(new BuildInfo(VERSION, PackageKind.ZIP,"x86_64",null)).isEmpty());
        assertTrue(release.assetFor(new BuildInfo(VERSION, PackageKind.UNKNOWN,"x86_64",null)).isEmpty());
    }
    @Test void rejectsDraftsAndPrereleases() throws Exception {
        String value = json("v1.10.0", "");
        assertTrue(parse(value.replace("\"draft\":false", "\"draft\":true")).isEmpty());
        assertTrue(parse(value.replace("\"prerelease\":false", "\"prerelease\":true")).isEmpty());
        assertTrue(parse(json("v1.10.0-rc1", "")).isEmpty());
        assertThrows(java.io.IOException.class, () -> parse("null"));
        assertThrows(java.io.IOException.class, () -> parse("invalid"));
    }
    @Test void refusesAssetsWithoutIntegrityOrFromAnotherRepository() throws Exception {
        String value = assetJson("Kubo-Setup.exe");
        for(String asset : List.of(value.replace(HASH,"bad"), value.replace("/Lightminedust/Kubo/", "/other/project/"),
                value.replace("https:","http:"), value.replace("123", "-1"), value.replace("123", "999999999999"),
                value.replace("Kubo-Setup.exe", "../Kubo.exe"))) {
            assertTrue(parse(json("v1.10.0",asset)).orElseThrow().assets().isEmpty());
        }
    }
    @Test void refusesAmbiguousDuplicatePackages() throws Exception {
        String a = assetJson("Kubo-Setup.exe");
        Release release = parse(json("v1.10.0", a + "," + a)).orElseThrow();
        assertTrue(release.assetFor(new BuildInfo(VERSION, PackageKind.EXE,"x86_64",null)).isEmpty());
    }
    @Test void checksRedirectHostsAndProtocol() {
        for(String url : List.of("http://github.com/a", "https:/missing-host", "https://github.com.evil.test/a", "https://user@github.com/a", "https://github.com:444/a", "file:///tmp/package"))
            assertFalse(ReleaseClient.allowed(java.net.URI.create(url)));
        assertTrue(ReleaseClient.allowed(java.net.URI.create("https://release-assets.githubusercontent.com/file?token=abc")));
    }
    @Test void knowsBuildVersionAndNormalisesArchitecture() {
        assertNotNull(BuildInfo.current().version());
        assertEquals("x86_64",BuildInfo.architecture("AMD64"));
        assertEquals("aarch64",BuildInfo.architecture("arm64"));
        assertEquals(PackageKind.UNKNOWN,PackageKind.of("other"));
        Arrays.stream(PackageKind.values()).forEach(kind -> assertEquals(kind,PackageKind.of(kind.id())));
    }
}
