package com.kubo.files;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.privilege.Privilege;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.files.Account;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.FileText;
import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.RemoteEntry;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Reads and changes the server's files over an open connection, and runs commands typed by the user.
 * Every command goes into the session history. Methods block: call them off the UI thread.
 */
public final class FileBrowser {

    private static final Duration QUICK = Duration.ofSeconds(15);
    private static final Duration SAVE = Duration.ofSeconds(60);
    // Copying, moving across disks or deleting a large folder can take a while.
    private static final Duration LONG = Duration.ofMinutes(10);

    private final SshConnection connection;
    private final CommandService commands;
    private final List<Consumer<CommandEntry>> listeners = new CopyOnWriteArrayList<>();

    /**
     * The session's root state, or null when this browser can never act as root. Read at every call, because the
     * user may turn root on and off while the window is open.
     */
    private com.kubo.privilege.RootSession root;

    /**
     * Gives this browser the session's root state, so file operations can run as root while root mode is on.
     */
    public void setRootSession(com.kubo.privilege.RootSession root) {
        this.root = root;
    }

    /**
     * True when the next command should run as root: root mode is on and the password, if one is needed, is here.
     */
    public boolean elevated() {
        return root != null && root.isElevated() && root.privilege() != null
                && root.privilege().available() && !root.privilege().needsPassword();
    }

    /**
     * Runs an already-formed sudo check, so the UI can tell whether the password is right before anything real
     * happens. Never recorded: the history has no business holding a password check.
     */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), QUICK);
    }

    public FileBrowser(SshConnection connection, CommandService commands) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public String target() {
        return connection.target().toString();
    }

    /**
     * Receives each command this browser sends on its own (not the ones typed by the user), once finished,
     * on the thread that ran it.
     */
    public void addListener(Consumer<CommandEntry> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * The connected user: id, name, groups and home folder.
     */
    public Optional<Account> account() {
        return FileCommands.parseAccount(run(FileCommands.ACCOUNT, Origin.FILES));
    }

    /**
     * GNU tools Kubo needs that the server lacks (BusyBox, BSD, macOS…); empty on a regular Linux server.
     */
    public List<String> missingGnuTools() {
        return FileCommands.parseMissingTools(run(FileCommands.GNU_TOOLS, Origin.FILES));
    }

    public DirectoryListing list(String path) {
        CommandEntry entry = run(ListingParser.command(path), Origin.FILES);
        if (!entry.ran()) {
            return DirectoryListing.failed(path, entry.error());
        }
        return ListingParser.parse(path, entry.output(), entry.error());
    }

    /**
     * Total size of everything under a path, empty when it cannot be measured in time.
     */
    public OptionalLong measure(String path) {
        CommandEntry entry = run("du -sb -- " + ShellQuote.quote(path) + " 2>/dev/null | cut -f1", Origin.FILES);
        String value = entry.output().strip();
        try {
            return entry.ran() && !value.isEmpty() ? OptionalLong.of(Long.parseLong(value)) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public FileText read(String path) {
        return FileCommands.parseRead(path, run(FileCommands.read(path), Origin.FILES));
    }

    /**
     * Saves a text over the file. Fails with {@link FileCommands#CHANGED_EXIT} when the file changed since
     * {@code expectedVersion}; on success the output is the new version.
     */
    public CommandEntry write(String path, String text, boolean crlf, Optional<String> expectedVersion) {
        byte[] bytes = FileCommands.encode(text, crlf);
        return run(FileCommands.write(path, bytes.length, expectedVersion), bytes, SAVE, Origin.FILES);
    }

    public CommandEntry delete(RemoteEntry entry) {
        return run(FileCommands.delete(entry), null, LONG, Origin.FILES);
    }

    /**
     * @param destination the full path of the copy, which must not exist yet
     */
    public CommandEntry copy(String source, String destination) {
        return run(FileCommands.copy(source, destination), null, LONG, Origin.FILES);
    }

    /**
     * Moves or renames; {@code destination} is the full new path, which must not exist yet.
     */
    public CommandEntry move(String source, String destination) {
        return run(FileCommands.move(source, destination), null, LONG, Origin.FILES);
    }

    public CommandEntry changeMode(String path, PermissionMode mode) {
        return run(FileCommands.changeMode(path, mode), Origin.FILES);
    }

    public CommandEntry createFolder(String path) {
        return run(FileCommands.createFolder(path), Origin.FILES);
    }

    public CommandEntry createFile(String path) {
        return run(FileCommands.createFile(path), Origin.FILES);
    }

    /**
     * Sends a local file or folder into a folder of the server. Folders travel as one tar stream.
     * On success the output is the path the upload ended at.
     *
     * @param onSent    bytes sent so far, out of {@link #uploadBytes}
     * @param cancelled checked while sending
     */
    public CommandEntry upload(LocalScan scan, String folder, String name, UploadCommands.Conflict conflict,
                               LongConsumer onSent, BooleanSupplier cancelled) {
        if (!scan.isDirectory()) {
            try (InputStream input = Files.newInputStream(scan.root())) {
                return run(UploadCommands.file(folder, name, scan.totalBytes(), conflict), input, onSent, cancelled,
                        LONG, Origin.FILES);
            } catch (IOException e) {
                return localFailure("Cannot read " + scan.root() + ": " + e.getMessage());
            }
        }
        // The archive is written by a second thread straight into the command's input.
        PipedInputStream input = new PipedInputStream(256 * 1024);
        String[] producerError = {null};
        Thread producer;
        try {
            PipedOutputStream pipe = new PipedOutputStream(input);
            producer = new Thread(() -> writeArchive(scan, pipe, producerError), "kubo-tar");
            producer.setDaemon(true);
            producer.start();
        } catch (IOException e) {
            return localFailure(e.getMessage());
        }
        try {
            CommandEntry entry = run(UploadCommands.folder(folder, name, scan.items().size(), conflict), input, onSent,
                    cancelled, LONG, Origin.FILES);
            closeQuietly(input);
            producer.join(5_000);
            if (!entry.success() && producerError[0] != null) {
                return new CommandEntry(entry.executedAt(), entry.command(), entry.asRoot(), entry.exitCode(),
                        entry.output(), producerError[0] + "\n" + entry.error(), entry.duration());
            }
            return entry;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return localFailure("Interrupted");
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * Saves a file or folder of the server into a local folder under {@code name}. It is written aside first
     * (a hidden ".kubo-part" file or ".kubo-download" folder) and only takes its name once complete, so a cancelled
     * or broken download leaves nothing behind.
     *
     * @param replace   overwrite a local file of the same name; folders are never replaced
     * @param onReceived bytes received so far
     */
    public Download download(RemoteEntry entry, Path folder, String name, boolean replace, LongConsumer onReceived,
                             BooleanSupplier cancelled) {
        Path target = folder.resolve(name);
        return entry.isDirectory()
                ? downloadFolder(entry, folder, target, onReceived, cancelled)
                : downloadFile(entry, folder, target, replace, onReceived, cancelled);
    }

    private Download downloadFile(RemoteEntry entry, Path folder, Path target, boolean replace, LongConsumer onReceived,
                                  BooleanSupplier cancelled) {
        Path part = folder.resolve("." + target.getFileName() + ".kubo-part");
        CommandEntry result;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part))) {
            result = readInto(DownloadCommands.file(entry.path()), out, onReceived, cancelled, QUICK,
                    Origin.FILES);
        } catch (IOException e) {
            deleteQuietly(part);
            return Download.failed("Cannot write " + part + ": " + e.getMessage());
        }
        try {
            if (cancelled.getAsBoolean() || result.error().equals("Cancelled")) {
                deleteQuietly(part);
                return Download.cancelledByUser();
            }
            var announced = DownloadCommands.announced(result.error());
            if (!result.success() || announced.isEmpty() || announced.get().size() != Files.size(part)) {
                deleteQuietly(part);
                String problem = DownloadCommands.problems(result.error());
                return Download.failed(problem.isEmpty() ? "Download incomplete, nothing was kept" : problem);
            }
            if (replace) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(part, target);
            }
            if (announced.get().modified() > 0) {
                Files.setLastModifiedTime(target, FileTime.fromMillis(announced.get().modified() * 1000));
            }
            return new Download(target, "", false, 0, 0);
        } catch (IOException e) {
            deleteQuietly(part);
            return Download.failed("Cannot save " + target + ": " + e.getMessage());
        }
    }

    private Download downloadFolder(RemoteEntry entry, Path folder, Path target, LongConsumer onReceived,
                                    BooleanSupplier cancelled) {
        Path temporary;
        try {
            temporary = Files.createTempDirectory(folder, ".kubo-download-");
        } catch (IOException e) {
            return Download.failed("Cannot write in " + folder + ": " + e.getMessage());
        }
        try {
            TarExtractor extractor = new TarExtractor(temporary);
            CommandEntry result;
            try (extractor) {
                result = readInto(DownloadCommands.folder(entry.path()), extractor, onReceived,
                        cancelled, QUICK, Origin.FILES);
            }
            if (cancelled.getAsBoolean() || result.error().equals("Cancelled")) {
                return Download.cancelledByUser();
            }
            String problems = DownloadCommands.problems(result.error());
            // tar exits with an error when some entries were unreadable, yet the archive it sent is complete.
            if (!extractor.finished() || extractor.rootName() == null) {
                return Download.failed(problems.isEmpty() ? "Download incomplete, nothing was kept" : problems);
            }
            Path root = temporary.resolve(extractor.rootName());
            if (!Files.isDirectory(root)) {
                return Download.failed("The server sent no folder");
            }
            Files.move(root, target);
            int unreadable = (int) problems.lines().filter(line -> line.contains("Cannot") || line.contains("denied")).count();
            return new Download(target, "", false, unreadable + extractor.skipped(), extractor.renamed());
        } catch (IOException e) {
            return Download.failed("Cannot save " + target + ": " + e.getMessage());
        } finally {
            deleteTree(temporary);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover hidden part file is harmless.
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(FileBrowser::deleteQuietly);
        } catch (IOException ignored) {
            // Best effort: only a hidden temporary folder can remain.
        }
    }

    /**
     * Bytes {@link #upload} will send for a scan.
     */
    public static long uploadBytes(LocalScan scan) {
        return scan.isDirectory() ? scan.tarBytes() : scan.totalBytes();
    }

    private static void writeArchive(LocalScan scan, PipedOutputStream pipe, String[] error) {
        try (pipe) {
            TarWriter tar = new TarWriter(pipe);
            for (LocalScan.Item item : scan.items()) {
                if (item.directory()) {
                    tar.directory(LocalScan.tarPath(item), item.modified());
                } else {
                    try (InputStream content = Files.newInputStream(item.path())) {
                        tar.file(LocalScan.tarPath(item), item.size(), item.modified(), content);
                    }
                }
            }
            tar.finish();
        } catch (IOException e) {
            // Either a local file could not be read, or the upload stopped reading (cancelled or refused).
            error[0] = "Local: " + e.getMessage();
        }
    }

    private static CommandEntry localFailure(String reason) {
        return new CommandEntry(Instant.now(), "(local)", false, CommandEntry.NOT_RUN, "", reason, Duration.ZERO);
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /**
     * Runs a command typed by the user, from the given folder.
     */
    public CommandEntry runIn(String directory, String command) {
        return run("cd -- " + ShellQuote.quote(directory) + " && " + command, Origin.USER);
    }

    /**
     * Resolves {@code cd target} from a folder; empty when the folder does not exist or cannot be entered.
     */
    public Optional<String> changeDirectory(String directory, String target) {
        CommandEntry entry = run("cd -- " + ShellQuote.quote(directory) + " && cd " + target.strip() + " && pwd",
                Origin.USER);
        String path = entry.output().strip();
        return entry.success() && path.startsWith("/") ? Optional.of(path) : Optional.empty();
    }

    private CommandEntry run(String command, Origin origin) {
        return run(command, null, QUICK, origin);
    }

    /**
     * Runs a command, as root when root mode is on.
     *
     * <p>When there is data to send, it goes on the same standard input the sudo password uses, so the wrapper that
     * always consumes exactly one password line is used and the password is written ahead of the data.
     */
    private CommandEntry run(String command, byte[] input, Duration timeout, Origin origin) {
        if (!elevated()) {
            return record(connection.exec(command, input, timeout), origin);
        }
        Privilege privilege = root.privilege();
        if (input == null) {
            Privilege.Command wrapped = privilege.asRoot(command);
            return recordAsRoot(connection.exec(wrapped.command(), wrapped.input(), timeout), command, origin);
        }
        Privilege.Command wrapped = privilege.asRootReadingInput(command);
        return recordAsRoot(connection.exec(wrapped.command(), join(wrapped.input(), input), timeout),
                command, origin);
    }

    private CommandEntry run(String command, InputStream input, LongConsumer onSent, BooleanSupplier cancelled,
                             Duration timeout, Origin origin) {
        if (!elevated()) {
            return record(connection.exec(command, input, onSent, cancelled, timeout), origin);
        }
        Privilege.Command wrapped = root.privilege().asRootReadingInput(command);
        InputStream stream = wrapped.input() == null ? input
                : new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(wrapped.input()), input);
        return recordAsRoot(connection.exec(wrapped.command(), stream, onSent, cancelled, timeout), command, origin);
    }

    /**
     * Reads a command's output, as root when root mode is on. The password goes on standard input and the file's
     * bytes come back on standard output, so the two never mix.
     */
    private CommandEntry readInto(String command, java.io.OutputStream sink, LongConsumer onReceived,
                                  BooleanSupplier cancelled, Duration timeout, Origin origin) {
        if (!elevated()) {
            return record(connection.execInto(command, sink, onReceived, cancelled, timeout), origin);
        }
        Privilege.Command wrapped = root.privilege().asRoot(command);
        // The password goes in. Closing the input without it is what sudo calls "no password was provided".
        return recordAsRoot(connection.execInto(wrapped.command(), wrapped.input(), sink, onReceived,
                cancelled, timeout), command, origin);
    }

    /**
     * The password line first, then the data. Only used where the command reads its own input.
     */
    private static byte[] join(byte[] password, byte[] data) {
        if (password == null || password.length == 0) {
            return data;
        }
        byte[] both = new byte[password.length + data.length];
        System.arraycopy(password, 0, both, 0, password.length);
        System.arraycopy(data, 0, both, password.length, data.length);
        return both;
    }

    /**
     * Records the plain command with its root flag — never the sudo wrapper, and never the password.
     */
    private CommandEntry recordAsRoot(CommandEntry raw, String plain, Origin origin) {
        return record(new CommandEntry(raw.executedAt(), plain, true, raw.exitCode(), raw.output(), raw.error(),
                raw.duration()), origin);
    }

    private CommandEntry record(CommandEntry entry, Origin origin) {
        commands.add(entry, origin);
        if (origin == Origin.FILES) {
            for (Consumer<CommandEntry> listener : listeners) {
                listener.accept(entry);
            }
        }
        return entry;
    }
}
