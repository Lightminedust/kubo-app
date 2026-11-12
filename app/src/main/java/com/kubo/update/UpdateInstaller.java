package com.kubo.update;

import com.kubo.platform.Host;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Prepares everything before shutdown. The external helper waits for this process before installing. */
public final class UpdateInstaller {
    public record Prepared(List<String> command, Path log, Path ready, Path staging, Path downloaded, Release.Asset asset) {
        public void start() throws IOException {
            // The ready screen can stay open for a while. Check the native installer again at handoff.
            ReleaseClient.verify(downloaded, asset);
            Files.deleteIfExists(ready);
            Process helper = new ProcessBuilder(command).directory(log.getParent().toFile())
                    .redirectOutput(log.toFile()).redirectErrorStream(true).start();
            // Do not exit Kubo if the helper could not even parse or initialise its script.
            for (int attempt = 0; attempt < 50; attempt++) {
                if (Files.isRegularFile(ready)) return;
                if (!helper.isAlive()) throw new IOException("The installer helper could not start. See " + log);
                try { Thread.sleep(100); }
                catch (InterruptedException e) { helper.destroy(); Thread.currentThread().interrupt(); throw new IOException("Installation cancelled", e); }
            }
            helper.destroy();
            throw new IOException("The installer helper did not become ready. See " + log);
        }

        public void discard() throws IOException { if (staging != null) deleteStaging(staging); }
    }

    public static Prepared prepare(BuildInfo build, Release.Asset asset, Path downloaded) throws IOException {
        ReleaseClient.verify(downloaded, asset);
        if (build.kind() == PackageKind.UNKNOWN || build.launcher() == null || !Files.isRegularFile(build.launcher()))
            throw new IOException("Install a packaged edition of Kubo to use automatic installation.");
        Path directory = downloaded.toAbsolutePath().getParent();
        boolean windows = build.kind().system() == Host.Kind.WINDOWS;
        if (!windows && !build.kind().portable() && !Files.isExecutable(Path.of("/usr/bin/pkexec")))
            throw new IOException("A graphical package installer (pkexec) is required. The downloaded package is in " + directory);
        Path target = build.kind().portable() ? portableRoot(build) : build.launcher().getParent();
        Path staging = null;
        Path stagedApp = directory; // unused for native installers
        Path backup = directory;
        try {
            if (build.kind().portable()) {
                staging = Files.createTempDirectory(target.getParent(), ".kubo-update-");
                stagedApp = PortableArchive.extract(downloaded, build.kind(), staging);
                backup = target.resolveSibling(target.getFileName() + ".previous-" + UUID.randomUUID());
            }
            Path script = directory.resolve(windows ? "install.ps1" : "install.sh");
            try (var input = UpdateInstaller.class.getResourceAsStream("/com/kubo/update/" + script.getFileName())) {
                if (input == null) throw new IOException("Missing installer helper");
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            }
            Path ready = directory.resolve("helper.ready");
            Path result = Path.of(System.getProperty("user.home"), ".kubo", "updates", "last-result.txt");
            List<String> command = new ArrayList<>(windows
                    ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    : List.of("/bin/sh", script.toString()));
            command.addAll(List.of(build.kind().id(), Long.toString(ProcessHandle.current().pid()),
                    target.toString(), stagedApp.toString(), backup.toString(), build.launcher().toString(),
                    downloaded.toAbsolutePath().toString(), ready.toString(), result.toString()));
            return new Prepared(List.copyOf(command), directory.resolve("install.log"), ready, staging, downloaded, asset);
        } catch (IOException | RuntimeException e) {
            if (staging != null) deleteStaging(staging);
            throw e;
        }
    }

    static Path portableRoot(BuildInfo build) throws IOException {
        Path launcher = build.launcher().toRealPath();
        Path root;
        if (build.kind() == PackageKind.ZIP && launcher.getFileName().toString().equalsIgnoreCase("Kubo.exe"))
            root = launcher.getParent();
        else if (build.kind() == PackageKind.TAR && launcher.getFileName().toString().equals("Kubo")
                && launcher.getParent().getFileName().toString().equals("bin")) root = launcher.getParent().getParent();
        else throw new IOException("Cannot locate this portable installation");
        Path config = root.resolve(build.kind() == PackageKind.ZIP ? "app/Kubo.cfg" : "lib/app/Kubo.cfg");
        if (root.getParent() == null || root.getParent().getParent() == null || !Files.isRegularFile(config))
            throw new IOException("Unrecognised portable installation layout");
        return root;
    }

    static void deleteStaging(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith(".kubo-update-") || root.getParent() == null)
            throw new IOException("Refusing to remove a non-staging directory");
        if (!Files.exists(root)) return;
        try (var entries = Files.walk(root)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }
}
