package com.kubo.ui.files;

import com.kubo.KuboApp;
import com.kubo.command.CommandEntry;
import com.kubo.model.files.Account;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.FileText;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.files.RemoteEntry.Type;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the file manager with a sample tree to PNG files, without a server.
 * Run it with: gradlew previewFiles
 */
public final class FilesPreview {

    private static final Instant MODIFIED = Instant.parse("2026-09-12T18:24:00Z");

    private FilesPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                renderHome(output);
                renderDenied(output);
                renderTree(output, "files-tree", false);
                renderTree(output, "files-loading", true);
                renderLargeFolder(output, "files-large", false);
                renderLargeFolder(output, "files-fit", true);
                renderEntryMenu(output);
                renderSpaceMenu(output);
                renderPermissions(output);
                renderDelete(output);
                renderRefresh(output);
                renderPicker(output);
                renderImport(output);
                renderDownload(output);
                renderDownloadCard(output);
                renderExpandedShell(output);
                com.kubo.ui.logs.LogsPreview.render(output);
                renderEditor(output, "editor", false);
                renderEditor(output, "editor-readonly", true);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    /**
     * Opens the way from the server root to /home/xana, as the real window does on start.
     */
    private static void openHome(BranchCanvas canvas) {
        canvas.setHome("/home/xana");
        canvas.simulate(0, 0.3);
        canvas.setListing(listing("/", root()));
        canvas.simulate(0.3, 0.7);
        canvas.setListing(listing("/home", homes()));
        canvas.simulate(0.7, 1.1);
        canvas.setListing(listing("/home/xana", home()));
        canvas.simulate(1.1, 2.4);
    }

    private static void renderHome(Path output) throws IOException {
        FilesView view = new FilesView(null);
        write(view, output, "files-home", () -> {
            view.choose(true);
            openHome(view.canvas());
            view.canvas().simulate(2.4, 3.4);
            CommandPrompt prompt = view.prompt();
            prompt.appendSystem(new CommandEntry(Instant.now(), "printf '%s' \"$HOME\"", false, 0, "/home/xana", "",
                    Duration.ofMillis(31)));
            prompt.appendSystem(new CommandEntry(Instant.now(), com.kubo.files.ListingParser.command("/"), false, 0,
                    "", "", Duration.ofMillis(88)));
            prompt.appendSystem(new CommandEntry(Instant.now(), com.kubo.files.ListingParser.command("/home"), false,
                    0, "", "", Duration.ofMillis(47)));
            prompt.appendSystem(new CommandEntry(Instant.now(), com.kubo.files.ListingParser.command("/home/xana"),
                    false, 0, "", "", Duration.ofMillis(52)));
        });
    }

    private static void renderDenied(Path output) throws IOException {
        FilesView view = new FilesView(null);
        write(view, output, "files-denied", () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);
            canvas.open(2, "/home/deploy");
            canvas.simulate(2.4, 2.8);
            canvas.setListing(com.kubo.model.files.DirectoryListing.failed("/home/deploy", "Permission denied"));
            canvas.simulate(2.8, 4.0);
        });
    }

    private static void renderTree(Path output, String name, boolean endLoading) throws IOException {
        FilesView view = new FilesView(null);
        write(view, output, name, () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);

            canvas.open(3, "/home/xana/projects");
            canvas.simulate(2.4, 2.8);
            canvas.setListing(listing("/home/xana/projects", projects()));
            canvas.simulate(2.8, 4.0);

            canvas.open(4, "/home/xana/projects/kubo");
            canvas.simulate(4.0, 4.4);
            canvas.setListing(listing("/home/xana/projects/kubo", kubo()));
            canvas.simulate(4.4, 5.6);

            if (endLoading) {
                canvas.open(5, "/home/xana/projects/kubo/app");
                canvas.simulate(5.6, 6.7);
            } else {
                canvas.selectPath(5, "/home/xana/projects/kubo/README.md");
                canvas.simulate(5.6, 6.7);
            }

            CommandPrompt prompt = view.prompt();
            prompt.appendResult("docker compose ps", new CommandEntry(Instant.now(), "docker compose ps", false, 0,
                    "NAME        IMAGE             STATUS          PORTS\nkubo-api    kubo/api:1.4      Up 3 days       0.0.0.0:8080->8080/tcp\n"
                            + "kubo-db     postgres:16       Up 3 days       5432/tcp", "", Duration.ofMillis(142)));
            prompt.appendResult("cat secrets.env", new CommandEntry(Instant.now(), "cat secrets.env", false, 1, "",
                    "cat: secrets.env: Permission denied", Duration.ofMillis(38)));
        });
    }

    private static void renderLargeFolder(Path output, String name, boolean fit) throws IOException {
        FilesView view = new FilesView(null);
        write(view, output, name, () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);

            canvas.open(3, "/home/xana/projects");
            canvas.setListing(listing("/home/xana/projects", projects()));
            canvas.simulate(2.4, 3.2);
            canvas.sort(4, BranchTree.SortKey.SIZE);
            canvas.simulate(3.2, 4.0);

            canvas.open(4, "/home/xana/projects/gemmie");
            canvas.setListing(listing("/home/xana/projects/gemmie", gemmie()));
            canvas.simulate(4.0, 5.0);

            canvas.open(5, "/home/xana/projects/gemmie/node_modules");
            canvas.setListing(listing("/home/xana/projects/gemmie/node_modules", nodeModules()));
            canvas.simulate(5.0, 6.5);
            canvas.selectPath(6, "/home/xana/projects/gemmie/node_modules/express");
            if (fit) {
                canvas.fit();
            }
            canvas.simulate(6.5, 8.5);
        });
    }

    private static final Account XANA = new Account(1000, "xana", Set.of("xana", "sudo", "docker"), "/home/xana");

    private static FilesView homeView() {
        FilesView view = new FilesView(null);
        view.setAccount(XANA);
        return view;
    }

    private static void settle(Node node) {
        node.setOpacity(1);
        node.setTranslateY(0);
    }

    private static void renderEntryMenu(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-menu", () -> {
            view.choose(true);
            openHome(view.canvas());
            view.showCard(view.canvas().menuFor("/home/xana/notes.md").orElseThrow());
            view.canvas().simulate(2.4, 3.0);
            view.applyCss();
            view.layout();
            settle(view.card());
        });
    }

    private static void renderSpaceMenu(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-space", () -> {
            view.choose(true);
            openHome(view.canvas());
            view.canvas().open(3, "/home/xana/projects");
            view.canvas().setListing(listing("/home/xana/projects", projects()));
            view.canvas().simulate(2.4, 3.6);
            view.showCard(view.canvas().spaceMenuFor("/home/xana/projects").orElseThrow());
            view.canvas().simulate(3.6, 4.2);
            view.applyCss();
            view.layout();
            settle(view.card());
        });
    }

    private static void renderPermissions(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-permissions", () -> {
            view.choose(true);
            openHome(view.canvas());
            var request = view.canvas().menuFor("/home/xana/server-setup.sh").orElseThrow();
            view.showCard(request);
            view.card().showPermissions(request.entry());
            view.canvas().simulate(2.4, 3.0);
            view.applyCss();
            view.layout();
            settle(view.card());
        });
    }

    private static void renderDelete(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-delete", () -> {
            view.choose(true);
            openHome(view.canvas());
            view.showCard(view.canvas().menuFor("/home/xana/backups").orElseThrow());
            view.card().showDelete();
            view.canvas().simulate(2.4, 3.0);
            view.applyCss();
            view.layout();
            settle(view.card());
        });
    }

    /**
     * A folder read again after a change: one entry dissolving, one appearing.
     */
    private static void renderRefresh(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-refresh", () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);
            canvas.refresh("/home/xana", "/home/xana/todo.md");
            canvas.simulate(2.4, 2.6);
            List<RemoteEntry> changed = new ArrayList<>(home().stream()
                    .filter(entry -> !entry.name().equals("notes.md") && !entry.name().equals("wallpaper.png")).toList());
            changed.add(file("todo.md", 640));
            canvas.setListing(listing("/home/xana", changed));
            canvas.simulate(2.6, 2.95);
        });
    }

    private static void renderPicker(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-picker", () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);
            var request = canvas.menuFor("/home/xana/notes.md").orElseThrow();
            view.transfer(request.entry(), request.folder(), true);
            view.applyCss();
            view.layout();
            BranchCanvas tree = view.picker().tree();
            tree.stop();
            tree.simulate(0, 0.3);
            tree.setListing(listing("/", root()));
            tree.simulate(0.3, 0.7);
            tree.setListing(listing("/home", homes()));
            tree.simulate(0.7, 1.1);
            tree.setListing(listing("/home/xana", home()));
            tree.simulate(1.1, 1.8);
            tree.open(3, "/home/xana/backups");
            tree.simulate(1.8, 2.2);
            tree.setListing(listing("/home/xana/backups", List.of(folder("2026-08", 12), folder("2026-09", 7),
                    folder("postgres", 31), file("restore.md", 2_100))));
            tree.simulate(2.2, 3.4);
            tree.selectPath(4, "/home/xana/backups/2026-09");
            tree.simulate(3.4, 3.8);
            settle(view.picker().getParent());
        });
    }

    /**
     * The local card open, a drag hovering the projects folder, and uploads in every state.
     */
    private static void renderImport(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-import", () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);
            canvas.open(3, "/home/xana/projects");
            canvas.setListing(listing("/home/xana/projects", projects()));
            canvas.simulate(2.4, 3.6);

            view.toggleLocal();
            Path documents = Path.of("C:\\Users\\xana\\Documents\\gemmie-release");
            Instant date = Instant.parse("2026-09-14T16:00:00Z");
            List<LocalCard.LocalEntry> local = List.of(
                    new LocalCard.LocalEntry(documents.resolve("assets"), "assets", true, 0, date),
                    new LocalCard.LocalEntry(documents.resolve("dist"), "dist", true, 0, date),
                    new LocalCard.LocalEntry(documents.resolve("screenshots"), "screenshots", true, 0, date),
                    new LocalCard.LocalEntry(documents.resolve("CHANGELOG.md"), "CHANGELOG.md", false, 12_400, date),
                    new LocalCard.LocalEntry(documents.resolve("docker-compose.prod.yml"), "docker-compose.prod.yml", false, 2_300, date),
                    new LocalCard.LocalEntry(documents.resolve("gemmie-2.1.0.tar.gz"), "gemmie-2.1.0.tar.gz", false, 184_000_000, date),
                    new LocalCard.LocalEntry(documents.resolve("nginx.conf"), "nginx.conf", false, 4_100, date),
                    new LocalCard.LocalEntry(documents.resolve("promo-video.mp4"), "promo-video.mp4", false, 48_000_000, date),
                    new LocalCard.LocalEntry(documents.resolve("release-notes.pdf"), "release-notes.pdf", false, 910_000, date),
                    new LocalCard.LocalEntry(documents.resolve("seed.sql"), "seed.sql", false, 6_700_000, date));
            view.applyCss();
            view.layout();
            view.localCard().showSample(documents, local, Set.of("dist", "nginx.conf", "gemmie-2.1.0.tar.gz"));
            view.localCard().setDestination(RemoteEntry.folder("/home/xana/projects"));
            settle(view.localCard());

            RemoteEntry projects = canvas.menuFor("/home/xana/projects").orElseThrow().entry();
            canvas.setDropTarget(RemoteEntry.folder("/home/xana/projects/gemmie"), null);
            TransferPanel transfers = view.transfers();
            transfers.addSample(false, "gemmie-2.1.0.tar.gz", documents, projects, Transfer.State.SENDING,
                    71_000_000, 184_000_000, 9_400_000, "");
            transfers.addSample(false, "dist", documents, projects, Transfer.State.WAITING, 0, 3_900_000, 0, "");
            transfers.addSample(false, "nginx.conf", documents, projects, Transfer.State.CONFLICT, 0, 4_100, 0,
                    "SOMETHING NAMED nginx.conf IS ALREADY IN /home/xana/projects");
            transfers.addSample(false, "CHANGELOG.md", documents, projects, Transfer.State.DONE, 12_400, 12_400,
                    0, "SENT TO /home/xana/projects/CHANGELOG.md");
            canvas.open(4, "/home/xana/projects/gemmie");
            canvas.setListing(listing("/home/xana/projects/gemmie", gemmie()));
            canvas.simulate(3.6, 4.9);
            view.applyCss();
            view.layout();
            view.localCard().renderFrame();
        });
    }

    /**
     * An entry carried from the tree onto this computer's card, a download running and one saved.
     */
    private static void renderDownload(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-download", () -> {
            BranchCanvas canvas = view.canvas();
            view.choose(true);
            openHome(canvas);
            view.toggleLocal();
            Path downloads = Path.of("C:\\Users\\xana\\Downloads");
            Instant date = Instant.parse("2026-09-14T16:00:00Z");
            view.applyCss();
            view.layout();
            view.localCard().showSample(downloads, List.of(
                    new LocalCard.LocalEntry(downloads.resolve("backups"), "backups", true, 0, date),
                    new LocalCard.LocalEntry(downloads.resolve("server-setup.sh"), "server-setup.sh", false, 2_204, date),
                    new LocalCard.LocalEntry(downloads.resolve("invoice-0912.pdf"), "invoice-0912.pdf", false, 184_000, date),
                    new LocalCard.LocalEntry(downloads.resolve("kubo-demo.mp4"), "kubo-demo.mp4", false, 48_000_000, date)),
                    Set.of());
            view.localCard().setDestination(canvas.currentDirectory());
            view.localCard().setDropRow(0);
            settle(view.localCard());

            RemoteEntry dump = canvas.menuFor("/home/xana/dump-2026-09.sql.gz").orElseThrow().entry();
            RemoteEntry notes = canvas.menuFor("/home/xana/notes.md").orElseThrow().entry();
            TransferPanel transfers = view.transfers();
            transfers.addSample(true, "dump-2026-09.sql.gz", downloads, dump, Transfer.State.SENDING, 312_000_000,
                    812_000_000, 11_200_000, "");
            transfers.addSample(true, "server-setup.sh", downloads, notes, Transfer.State.DONE, 2_204, 2_204, 0,
                    "SAVED TO C:\\Users\\xana\\Downloads\\server-setup.sh");
            canvas.simulate(2.4, 3.3);
            view.applyCss();
            view.layout();
            view.localCard().renderFrame();
        });
    }

    private static void renderDownloadCard(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-download-card", () -> {
            view.choose(true);
            openHome(view.canvas());
            var request = view.canvas().menuFor("/home/xana/backups").orElseThrow();
            view.showCard(request);
            view.card().showDownload(request.entry());
            view.canvas().simulate(2.4, 3.0);
            view.applyCss();
            view.layout();
            settle(view.card());
        });
    }

    private static void renderExpandedShell(Path output) throws IOException {
        FilesView view = homeView();
        write(view, output, "files-shell-expanded", () -> {
            view.choose(true);
            openHome(view.canvas());
            view.prompt().setExpanded(true);
            view.prompt().appendSystem(new CommandEntry(Instant.now(), com.kubo.files.ListingParser.command("/home/xana"),
                    false, 0, "", "", Duration.ofMillis(52)));
            view.prompt().appendResult("df -h /", new CommandEntry(Instant.now(), "df -h /", false, 0,
                    "Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda1        96G   31G   61G  34% /", "",
                    Duration.ofMillis(44)));
            view.canvas().simulate(2.4, 2.8);
        });
    }

    private static void renderEditor(Path output, String name, boolean readOnly) throws IOException {
        String path = "/home/xana/docker/compose.yml";
        FileEditor editor = new FileEditor(null, path);
        Scene scene = new Scene(editor, 1180, 820, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        editor.applyCss();
        editor.layout();
        String text = composeFile();
        editor.show(new FileText(path, text, "1726400000.123456789:7412:393221", false, readOnly ? "No write permission" : "", ""));
        if (!readOnly) {
            editor.type(text.replace("image: postgres:16", "image: postgres:17\n    shm_size: 256mb"));
        }
        editor.applyCss();
        editor.layout();

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        editor.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private static String composeFile() {
        StringBuilder text = new StringBuilder("""
                # Kubo sample stack
                services:
                  api:
                    image: kubo/api:1.4
                    restart: unless-stopped
                    ports:
                      - "8080:8080"
                    environment:
                      DATABASE_URL: postgres://kubo@db:5432/kubo
                      LOG_LEVEL: info
                    depends_on:
                      - db

                  db:
                    image: postgres:16
                    restart: unless-stopped
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                    environment:
                    \tPOSTGRES_USER: kubo
                      POSTGRES_DB: kubo

                """);
        for (int i = 1; i <= 90; i++) {
            text.append("  worker-").append(i).append(":\n    image: kubo/worker:1.4\n    command: [\"run\", \"--queue\", \"q")
                    .append(i).append("\"]\n");
        }
        text.append("\nvolumes:\n  pgdata:\n");
        return text.toString();
    }

    private static List<RemoteEntry> root() {
        return List.of(folder("bin", 0), folder("boot", 6), folder("dev", 0), folder("etc", 142), folder("home", 3),
                lockedFolder("lost+found"), folder("media", 0), folder("mnt", 0), folder("opt", 4), folder("proc", 0),
                lockedFolder("root"), folder("run", 31), folder("srv", 2), folder("sys", 0), folder("tmp", 9),
                folder("usr", 12), folder("var", 14), link("lib", "usr/lib"), link("sbin", "usr/sbin"));
    }

    private static List<RemoteEntry> homes() {
        return List.of(folder("xana", 14), lockedFolder("deploy"), lockedFolder("postgres"),
                file("welcome.txt", 1_200), file("shared-notes.md", 8_400));
    }

    private static List<RemoteEntry> gemmie() {
        return List.of(folder("node_modules", 526), folder("src", 48), folder("public", 31), folder("scripts", 6),
                folder(".git", 14), file("package.json", 2_300), file("package-lock.json", 412_000),
                file("vite.config.ts", 900), file("README.md", 3_100), file("gemmie_prod.zip", 88_000_000));
    }

    private static List<RemoteEntry> nodeModules() {
        String[] names = {"express", "react", "react-dom", "vite", "typescript", "lodash", "axios", "zod", "prisma",
                "eslint", "prettier", "esbuild", "rollup", "postcss", "tailwindcss", "dotenv", "cors", "jsonwebtoken",
                "bcrypt", "sharp", "chalk", "commander", "debug", "ms", "semver", "glob", "minimatch", "uuid", "ws",
                "@types", "@babel", "@esbuild", "@rollup", "@vitejs", "@prisma", "@eslint", "@tailwindcss"};
        List<RemoteEntry> entries = new ArrayList<>();
        Random random = new Random(9);
        for (int i = 0; i < 520; i++) {
            String name = i < names.length ? names[i] : names[i % names.length] + "-" + Integer.toString(i, 36);
            entries.add(folder(name, 1 + random.nextInt(i < names.length ? 90 : 12)));
        }
        entries.add(file(".package-lock.json", 380_000));
        entries.add(file(".yarn-integrity", 2_000));
        return entries;
    }

    private static void write(FilesView view, Path output, String name, Runnable prepare) throws IOException {
        // Previews show the ROOT button, so its place in the header is checked like everything else.
        view.setRootSession(new com.kubo.privilege.RootSession(com.kubo.privilege.RootAccess.SUDO_PASSWORD));
        Scene scene = new Scene(view, 1440, 880, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        view.canvas().stop();
        view.applyCss();
        view.layout();
        prepare.run();
        view.applyCss();
        view.layout();

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        view.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private static DirectoryListing listing(String path, List<RemoteEntry> entries) {
        List<RemoteEntry> placed = entries.stream()
                .map(entry -> new RemoteEntry(entry.name(), RemoteEntry.join(path, entry.name()), entry.type(),
                        entry.sizeBytes(), entry.modified(), entry.permissions(), entry.owner(), entry.group(),
                        entry.linkTarget(), entry.childCount(), entry.readable()))
                .sorted((a, b) -> a.isDirectory() != b.isDirectory()
                        ? (a.isDirectory() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()))
                .toList();
        return new DirectoryListing(path, placed, false, "");
    }

    private static List<RemoteEntry> home() {
        return List.of(
                folder("projects", 9), folder("backups", 42), folder("docker", 6), folder("downloads", 138),
                folder("scripts", 17), folder(".ssh", 4), folder(".config", 23), lockedFolder("private"),
                file("notes.md", 4_812), file("server-setup.sh", 2_204), file("dump-2026-09.sql.gz", 812_000_000),
                file(".bashrc", 3_771), link("logs", "/var/log/kubo"), file("wallpaper.png", 2_400_000));
    }

    private static List<RemoteEntry> projects() {
        return List.of(folder("kubo", 11), folder("mbaliscope", 14), folder("site", 32), folder("gemmie", 58),
                folder("teyalink", 21), folder("sandbox", 3), folder("archive", 204), folder("api-gateway", 16),
                file("ideas.txt", 1_020));
    }

    private static List<RemoteEntry> kubo() {
        List<RemoteEntry> entries = new ArrayList<>(List.of(
                folder("app", 4), folder("gradle", 3), folder(".git", 12), folder("docs", 7), folder("build", 0),
                file("README.md", 6_400), file("build.gradle.kts", 1_800), file("settings.gradle.kts", 420),
                file("gradlew", 8_700), file("gradlew.bat", 2_900), file("kubo-demo.mp4", 48_000_000),
                file("release-0.1.zip", 21_000_000), file(".gitignore", 180)));
        Random random = new Random(3);
        for (int i = 0; i < 14; i++) {
            entries.add(file("capture-" + (i + 1) + ".png", 40_000 + random.nextInt(3_000_000)));
        }
        return entries;
    }

    private static RemoteEntry folder(String name, int children) {
        return new RemoteEntry(name, "/" + name, Type.DIRECTORY, 4096, MODIFIED, "drwxr-xr-x", "xana", "xana", "",
                children, true);
    }

    private static RemoteEntry lockedFolder(String name) {
        return new RemoteEntry(name, "/" + name, Type.DIRECTORY, 4096, MODIFIED, "drwx------", "root", "root", "",
                RemoteEntry.UNKNOWN, false);
    }

    private static RemoteEntry file(String name, long size) {
        return new RemoteEntry(name, "/" + name, Type.FILE, size, MODIFIED, "-rw-r--r--", "xana", "xana", "",
                RemoteEntry.UNKNOWN, true);
    }

    private static RemoteEntry link(String name, String target) {
        return new RemoteEntry(name, "/" + name, Type.LINK, 13, MODIFIED, "lrwxrwxrwx", "xana", "xana", target,
                RemoteEntry.UNKNOWN, true);
    }
}
