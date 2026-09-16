package com.kubo.ui.docker;

import com.kubo.command.CommandEntry;
import com.kubo.docker.DockerCommands;
import com.kubo.docker.ContainerFiles;
import com.kubo.model.files.RemoteEntry;
import com.kubo.docker.DockerService;
import com.kubo.model.docker.ContainerDetails;
import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.DockerStatus;
import com.kubo.model.docker.EnvVar;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.Mount;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.privilege.Privilege;
import com.kubo.ui.scope.Format;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Docker screen: the cluster of containers grouped into stacks in the middle, the selected container's details,
 * environment and one-click actions on the right, a console at the bottom for logs or in-container commands, and
 * side views for images and networks. Actions that need root go through {@link Privilege}, always after asking.
 */
public final class DockerScope extends StackPane {

    private enum View { CONTAINERS, IMAGES, NETWORKS }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Color RUNNING = Color.web("#35e0a1");
    private static final Color STOPPED = Color.web("#ff5a6e");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color PUBLIC = Color.web("#ff2d55");
    private static final Color INTERNAL = Color.web("#8a4dff");
    private static final Color LOCAL = Color.web("#8a8fb0");

    private final DockerService docker;
    private final DockerStatus status;
    private final Privilege privilege;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-docker");
        thread.setDaemon(true);
        return thread;
    });

    private final DockerCluster cluster = new DockerCluster();
    private final ImagesView imagesView = new ImagesView(this::removeImage, this::pruneImages);
    private final NetworksView networksView = new NetworksView();
    private final StackPane center = new StackPane();
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(3), event -> refresh(false)));

    private final Button containersTab = tab("CONTAINERS");
    private final Button imagesTab = tab("IMAGES");
    private final Button networksTab = tab("NETWORKS");

    private final Label notice = new Label();
    private final PauseTransition noticeTimer = new PauseTransition(Duration.seconds(5));

    // Details
    private final Label nameLabel = label("SELECT A CONTAINER", "files-title");
    private final Label idLabel = label("", "mono-muted");
    private final Label healthBadge = label("", "badge");
    private final Label imageValue = label("—", "metric-value");
    private final Label stateValue = label("—", "metric-value");
    private final Label commandValue = label("—", "mono");
    private final Label restartValue = label("—", "metric-value");
    private final VBox portsBox = new VBox(5);
    private final VBox mountsBox = new VBox(5);
    private final VBox envBox = new VBox(4);
    private final Button restartStackButton = actionButton("⟳ RESTART STACK", "ghost-button");

    // Actions
    private final Button startButton = actionButton("▶ START", "primary-button");
    private final Button stopButton = actionButton("⏹ STOP", "ghost-button");
    private final Button restartButton = actionButton("⟳ RESTART", "ghost-button");
    private final Button deleteButton = actionButton("🗑 DELETE", "danger-button");
    private final Label actionStatus = label("", "card-note");

    // Console
    private final VBox consoleOut = new VBox(2);
    private final ScrollPane consoleScroll = new ScrollPane(consoleOut);
    private final Label consoleTitle = label("LOGS", "series");
    private final TextField execInput = new TextField();
    private final Button logsTab = actionButton("LOGS", "mode-toggle");
    private final Button shellTab = actionButton("SHELL", "mode-toggle");
    private final Button filesTab = actionButton("FILES", "mode-toggle");
    private HBox execRow;

    /** Holds the root button once the window hands over the session shared by every screen. */
    private final HBox rootSlot = new HBox();
    private View view = View.CONTAINERS;
    private DockerContainer selected;
    private final Set<String> revealed = new HashSet<>();
    /** What the panel at the bottom is showing. */
    private enum Console { LOGS, SHELL, FILES }

    private Console console = Console.LOGS;
    /** Where the container browser currently is, inside the container. */
    private String containerPath = "/";
    private boolean deleteArmed;
    private StackPane overlay;

    public DockerScope(DockerService docker, DockerStatus status, Privilege privilege) {
        this(docker, status, privilege, false);
    }

    /**
     * Builds the screen without a live service, for previews: pass the containers straight to the cluster.
     */
    DockerScope(DockerStatus status, List<DockerContainer> containers) {
        this(null, status, null, false);
        cluster.setContainers(containers);
    }

    private DockerScope(DockerService docker, DockerStatus status, Privilege privilege, boolean unused) {
        this.docker = docker;
        this.status = status;
        this.privilege = privilege;
        getStyleClass().add("scope-view");

        cluster.setVersion(status.version());
        cluster.setOnSelect(this::showContainer);
        cluster.setOnOpen(container -> {
            showContainer(container);
            setConsoleMode(Console.LOGS);
        });
        cluster.setOnRestartStack(this::restartStack);

        center.getChildren().addAll(cluster, imagesView, networksView);
        imagesView.setVisible(false);
        networksView.setVisible(false);

        notice.getStyleClass().add("files-notice");
        notice.setVisible(false);
        notice.setMouseTransparent(true);
        StackPane.setAlignment(notice, Pos.BOTTOM_LEFT);
        StackPane.setMargin(notice, new Insets(0, 0, 18, 36));
        noticeTimer.setOnFinished(event -> notice.setVisible(false));
        center.getChildren().add(notice);

        BorderPane layout = new BorderPane(center);
        layout.setTop(buildHeader());
        layout.setRight(buildDetails());
        layout.setBottom(buildConsole());
        getChildren().add(layout);

        showContainer(null);
        poll.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Gives this screen the session's root state, which puts the root button in its header.
     */
    public void setRootSession(com.kubo.privilege.RootSession session) {
        rootSlot.getChildren().setAll(new com.kubo.ui.RootButton(session,
                (command, done) -> worker.execute(() -> {
                    CommandEntry entry = docker.verify(command);
                    Platform.runLater(() -> done.accept(entry.success()));
                }),
                this::showOverlay, this::hideOverlay));
    }

    /**
     * Starts polling and reads the containers once now.
     */
    public void start() {
        refresh();
        poll.play();
    }

    public void shutdown() {
        poll.stop();
        cluster.stop();
        if (privilege != null) {
            privilege.wipe();
        }
        worker.shutdownNow();
    }

    // Reading

    /**
     * A manual refresh: shows the scan sweep and the loading shimmer.
     */
    private void refresh() {
        refresh(true);
    }

    /**
     * @param manual true for a user-triggered refresh, which animates; false for the silent 3 s poll
     */
    private void refresh(boolean manual) {
        switch (view) {
            case CONTAINERS -> {
                if (manual) {
                    cluster.flash();
                    cluster.setLoading(true);
                }
                worker.execute(() -> {
                    List<DockerContainer> containers = docker.containers();
                    Platform.runLater(() -> {
                        cluster.setLoading(false);
                        cluster.setContainers(containers);
                        if (selected != null) {
                            containers.stream().filter(c -> c.id().equals(selected.id())).findFirst()
                                    .ifPresentOrElse(this::updateSelected, () -> showContainer(null));
                        }
                    });
                });
            }
            case IMAGES -> {
                if (manual) {
                    imagesView.showLoading();
                }
                worker.execute(() -> {
                    var images = docker.images();
                    Platform.runLater(() -> imagesView.setImages(images));
                });
            }
            case NETWORKS -> {
                if (manual) {
                    networksView.showLoading();
                }
                worker.execute(() -> {
                    var networks = docker.networks();
                    Platform.runLater(() -> networksView.setNetworks(networks));
                });
            }
        }
    }

    private void updateSelected(DockerContainer container) {
        selected = container;
        stateValue.setText(container.state().name());
        stateValue.setStyle("-fx-text-fill: " + web(container.state().isRunning() ? RUNNING : STOPPED) + ";");
        setHealth(container.health());
        restartStackButton.setVisible(container.inStack());
        restartStackButton.setManaged(container.inStack());
        updateActions(container.state());
    }

    private void showContainer(DockerContainer container) {
        selected = container;
        deleteArmed = false;
        actionStatus.setText("");
        cluster.setBusy(Set.of());
        if (container == null) {
            nameLabel.setText("SELECT A CONTAINER");
            idLabel.setText("");
            setHealth(Health.NONE);
            restartStackButton.setVisible(false);
            restartStackButton.setManaged(false);
            for (Label value : new Label[]{imageValue, stateValue, commandValue, restartValue}) {
                value.setText("—");
            }
            portsBox.getChildren().clear();
            mountsBox.getChildren().clear();
            envBox.getChildren().clear();
            updateActions(null);
            return;
        }
        cluster.select(container.id());
        nameLabel.setText(container.name());
        idLabel.setText(container.shortId() + (container.inStack() ? "  ·  " + container.project() + " / "
                + container.service() : ""));
        imageValue.setText(container.image());
        updateSelected(container);
        commandValue.setText("READING…");
        loadDetails(container);
        switch (console) {
            case LOGS -> loadLogs(container);
            case SHELL -> consoleOut.getChildren().clear();
            case FILES -> browse(container, "/");
        }
    }

    private void loadDetails(DockerContainer container) {
        cluster.setBusy(Set.of(container.id()));
        worker.execute(() -> {
            ContainerDetails details = docker.inspect(container.id());
            Platform.runLater(() -> {
                if (selected == null || !selected.id().equals(container.id())) {
                    return;
                }
                cluster.setBusy(Set.of());
                if (details == null) {
                    commandValue.setText("—");
                    return;
                }
                commandValue.setText(details.command().isEmpty() ? "—" : details.command());
                restartValue.setText(details.restartPolicy());
                fillPorts(details.ports());
                fillMounts(details.mounts());
                fillEnv(details.env());
            });
        });
    }

    private void setHealth(Health health) {
        healthBadge.setVisible(health.isKnown());
        healthBadge.setManaged(health.isKnown());
        if (!health.isKnown()) {
            return;
        }
        healthBadge.setText(health.name());
        Color color = switch (health) {
            case HEALTHY -> RUNNING;
            case UNHEALTHY -> STOPPED;
            case STARTING -> AMBER;
            case NONE -> LOCAL;
        };
        healthBadge.setStyle("-fx-text-fill: " + web(color) + "; -fx-border-color: " + web(color) + ";");
    }

    private void fillPorts(List<PortMapping> ports) {
        portsBox.getChildren().clear();
        if (ports.isEmpty()) {
            portsBox.getChildren().add(label("NONE", "mono-muted"));
            return;
        }
        for (PortMapping port : ports) {
            Circle dot = new Circle(4, port.exposure() == Exposure.PUBLIC ? PUBLIC
                    : port.exposure() == Exposure.INTERNAL ? INTERNAL : LOCAL);
            String text = port.isPublished()
                    ? (port.hostIp().isEmpty() ? "0.0.0.0" : port.hostIp()) + ":" + port.hostPort()
                    + "  →  " + port.containerPort() + "/" + port.protocol()
                    : port.containerPort() + "/" + port.protocol() + "  (INTERNAL)";
            HBox row = new HBox(9, dot, label(text, "mono"));
            row.setAlignment(Pos.CENTER_LEFT);
            portsBox.getChildren().add(row);
        }
    }

    private void fillMounts(List<Mount> mounts) {
        mountsBox.getChildren().clear();
        if (mounts.isEmpty()) {
            mountsBox.getChildren().add(label("NONE", "mono-muted"));
            return;
        }
        for (Mount mount : mounts) {
            Label kind = label(mount.isBind() ? "BIND" : "VOL", "badge");
            Label text = label(clip(mount.source()) + "  →  " + mount.destination()
                    + (mount.readWrite() ? "" : "  (RO)"), "mono");
            text.setWrapText(true);
            HBox row = new HBox(9, kind, text);
            row.setAlignment(Pos.TOP_LEFT);
            mountsBox.getChildren().add(row);
        }
    }

    private void fillEnv(List<EnvVar> env) {
        envBox.getChildren().clear();
        revealed.clear();
        if (env.isEmpty()) {
            envBox.getChildren().add(label("NONE", "mono-muted"));
            return;
        }
        for (EnvVar var : env) {
            envBox.getChildren().add(envRow(var));
        }
    }

    private HBox envRow(EnvVar var) {
        Label key = label(var.key(), "env-key");
        key.setMinWidth(Region.USE_PREF_SIZE);
        Label value = label(displayEnv(var), "env-value");
        value.setWrapText(true);
        HBox.setHgrow(value, Priority.ALWAYS);
        value.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(8, key, value);
        row.setAlignment(Pos.TOP_LEFT);
        if (var.sensitive()) {
            Button eye = new Button("👁");
            eye.getStyleClass().add("env-eye");
            eye.setFocusTraversable(false);
            eye.setOnAction(event -> {
                if (!revealed.add(var.key())) {
                    revealed.remove(var.key());
                }
                value.setText(displayEnv(var));
            });
            row.getChildren().add(eye);
        }
        return row;
    }

    private String displayEnv(EnvVar var) {
        return var.sensitive() && !revealed.contains(var.key()) ? var.masked() : var.value();
    }

    // Actions

    private void updateActions(ContainerState state) {
        boolean has = state != null;
        startButton.setDisable(!has || !state.canStart());
        stopButton.setDisable(!has || !state.canStop());
        restartButton.setDisable(!has || !state.isRunning());
        deleteButton.setDisable(!has);
        deleteButton.setText(deleteArmed ? "CONFIRM DELETE" : "🗑 DELETE");
    }

    private void act(String verb, java.util.function.Function<String, CommandEntry> action) {
        DockerContainer container = selected;
        if (container == null) {
            return;
        }
        runWithRoot(() -> {
            setActionsBusy(true);
            cluster.setBusy(Set.of(container.id()));
            actionStatus.getStyleClass().setAll("label", "card-note");
            actionStatus.setText(verb + " " + container.name() + "…");
            worker.execute(() -> {
                CommandEntry result = action.apply(container.id());
                Platform.runLater(() -> {
                    setActionsBusy(false);
                    cluster.setBusy(Set.of());
                    if (result.success()) {
                        actionStatus.getStyleClass().setAll("label", "card-success");
                        actionStatus.setText(verb + " DONE");
                    } else {
                        actionStatus.getStyleClass().setAll("label", "card-problem");
                        actionStatus.setText(problem(result));
                    }
                    refresh();
                });
            });
        });
    }

    private void delete() {
        if (!deleteArmed) {
            deleteArmed = true;
            deleteButton.setText("CONFIRM DELETE");
            actionStatus.getStyleClass().setAll("label", "card-problem");
            actionStatus.setText("A CONTAINER CANNOT BE UNDELETED. CLICK AGAIN TO CONFIRM.");
            return;
        }
        deleteArmed = false;
        act("DELETE", docker::remove);
        Platform.runLater(() -> showContainer(null));
    }

    private void restartStack(String project) {
        // The stack's containers are read fresh from the daemon so a just-added one is included.
        runWithRoot(() -> {
            notice("RESTARTING STACK " + project.toUpperCase(Locale.ROOT) + "…", false);
            worker.execute(() -> {
                List<String> stackIds = docker.containers().stream()
                        .filter(c -> project.equals(c.project())).map(DockerContainer::id).toList();
                if (stackIds.isEmpty()) {
                    Platform.runLater(() -> notice("STACK " + project + " HAS NO CONTAINERS", true));
                    return;
                }
                CommandEntry result = docker.restartStack(stackIds);
                Platform.runLater(() -> {
                    notice(result.success() ? "STACK " + project.toUpperCase(Locale.ROOT) + " RESTARTED"
                            : problem(result), !result.success());
                    refresh();
                });
            });
        });
    }

    private void prune() {
        runWithRoot(() -> {
            notice("FREEING DISK SPACE…", false);
            worker.execute(() -> {
                CommandEntry result = docker.prune();
                long freed = DockerCommands.parseReclaimed(result.output());
                Platform.runLater(() -> {
                    notice(result.success() ? "FREED " + Format.bytes(freed) : problem(result), !result.success());
                    refresh();
                });
            });
        });
    }

    private void showRun() {
        RunDialog dialog = new RunDialog((request, done) -> runWithRoot(() -> worker.execute(() -> {
            CommandEntry result = docker.run(request.image(), request.name(), request.ports());
            Platform.runLater(() -> {
                if (result.success()) {
                    done.accept(null);
                    hideOverlay();
                    notice("STARTED " + request.image(), false);
                    refresh();
                } else {
                    done.accept(problem(result));
                }
            });
        })), this::hideOverlay);
        showOverlay(dialog);
    }

    private void removeImage(com.kubo.model.docker.DockerImage image) {
        runWithRoot(() -> {
            notice("REMOVING " + image.reference() + "…", false);
            worker.execute(() -> {
                CommandEntry result = docker.removeImage(image.dangling() ? image.id() : image.reference());
                Platform.runLater(() -> {
                    notice(result.success() ? "REMOVED " + image.reference() : problem(result), !result.success());
                    refresh();
                });
            });
        });
    }

    private void pruneImages() {
        runWithRoot(() -> {
            notice("SWEEPING DANGLING IMAGES…", false);
            worker.execute(() -> {
                CommandEntry result = docker.pruneImages();
                long freed = DockerCommands.parseReclaimed(result.output());
                Platform.runLater(() -> {
                    notice(result.success() ? "SWEPT  ·  FREED " + Format.bytes(freed) : problem(result), !result.success());
                    refresh();
                });
            });
        });
    }

    private void setActionsBusy(boolean busy) {
        for (Button button : new Button[]{startButton, stopButton, restartButton, deleteButton}) {
            button.setDisable(busy);
        }
        if (!busy && selected != null) {
            updateActions(selected.state());
        }
    }

    // Console

    private void setConsoleMode(Console mode) {
        console = mode;
        for (Button tab : new Button[]{logsTab, shellTab, filesTab}) {
            tab.getStyleClass().remove("mode-toggle-active");
        }
        (mode == Console.SHELL ? shellTab : mode == Console.FILES ? filesTab : logsTab)
                .getStyleClass().add("mode-toggle-active");
        consoleTitle.setText(switch (mode) {
            case LOGS -> "LOGS";
            case SHELL -> "SHELL  ·  docker exec";
            case FILES -> "FILES  ·  READ-ONLY";
        });
        if (execRow != null) {
            execRow.setVisible(mode == Console.SHELL);
            execRow.setManaged(mode == Console.SHELL);
        }
        consoleOut.getChildren().clear();
        if (selected == null) {
            appendConsole("SELECT A CONTAINER.", "prompt-meta");
            return;
        }
        switch (mode) {
            case LOGS -> loadLogs(selected);
            case SHELL -> appendConsole("RUNS ONE COMMAND INSIDE " + selected.name()
                    + " (docker exec, non-interactive).", "prompt-meta");
            case FILES -> browse(selected, containerPath);
        }
    }

    // Looking inside a container

    /**
     * Lists one folder inside the selected container. Read-only: Kubo shows what is in the image and what the
     * container has written since, and changes nothing - a container is meant to be rebuilt, not edited by hand.
     */
    private void browse(DockerContainer container, String path) {
        containerPath = path == null || path.isBlank() ? "/" : path;
        consoleOut.getChildren().clear();
        appendConsole("READING " + containerPath + "…", "prompt-meta");
        String asked = containerPath;
        worker.execute(() -> {
            ContainerFiles.Listing listing = docker.browse(container.id(), asked);
            Platform.runLater(() -> {
                if (selected == null || !selected.id().equals(container.id()) || console != Console.FILES
                        || !asked.equals(containerPath)) {
                    return;
                }
                showListing(container, asked, listing);
            });
        });
    }

    private void showListing(DockerContainer container, String path, ContainerFiles.Listing listing) {
        consoleOut.getChildren().clear();
        consoleOut.getChildren().add(label(container.name() + "  ·  " + path, "prompt-command"));
        if (!path.equals("/")) {
            consoleOut.getChildren().add(row("↑  ..", "prompt-meta", () -> browse(container, parentOf(path))));
        }
        if (listing.failed()) {
            appendConsole(listing.problem().toUpperCase(Locale.ROOT), "card-problem");
            return;
        }
        for (RemoteEntry entry : listing.entries()) {
            String mark = entry.isDirectory() ? "▸ " : entry.type() == RemoteEntry.Type.LINK ? "→ " : "  ";
            String text = mark + entry.name() + (entry.isDirectory() ? "" : "   " + bytes(entry.sizeBytes()));
            if (entry.isDirectory()) {
                consoleOut.getChildren().add(row(text, "prompt-output", () -> browse(container, entry.path())));
            } else if (ContainerFiles.viewable(entry)) {
                consoleOut.getChildren().add(row(text, "prompt-output", () -> view(container, entry)));
            } else {
                consoleOut.getChildren().add(label(text, "prompt-meta"));
            }
        }
        if (listing.entries().isEmpty()) {
            appendConsole("THIS FOLDER IS EMPTY.", "prompt-meta");
        }
        if (listing.truncated()) {
            appendConsole("… ONLY THE FIRST " + ContainerFiles.MAX_ENTRIES + " ENTRIES ARE SHOWN.",
                    "prompt-meta");
        }
    }

    private void view(DockerContainer container, RemoteEntry entry) {
        consoleOut.getChildren().clear();
        appendConsole("READING " + entry.path() + "…", "prompt-meta");
        worker.execute(() -> {
            ContainerFiles.FileRead read = docker.readInContainer(container.id(), entry.path());
            Platform.runLater(() -> {
                if (selected == null || !selected.id().equals(container.id()) || console != Console.FILES) {
                    return;
                }
                consoleOut.getChildren().clear();
                consoleOut.getChildren().add(row("↑  " + parentOf(entry.path()), "prompt-meta",
                        () -> browse(container, parentOf(entry.path()))));
                consoleOut.getChildren().add(label(entry.path(), "prompt-command"));
                if (read.failed()) {
                    appendConsole(read.problem().toUpperCase(Locale.ROOT), "card-problem");
                    return;
                }
                appendConsole(read.text().isBlank() ? "THIS FILE IS EMPTY." : read.text().stripTrailing(),
                        read.text().isBlank() ? "prompt-meta" : "prompt-output");
                if (entry.sizeBytes() >= ContainerFiles.MAX_VIEW_BYTES) {
                    appendConsole("… ONLY THE FIRST " + bytes(ContainerFiles.MAX_VIEW_BYTES) + " ARE SHOWN.",
                            "prompt-meta");
                }
            });
        });
    }

    /**
     * A console line you can click.
     */
    private Label row(String text, String styleClass, Runnable action) {
        Label label = label(text, styleClass);
        label.setCursor(javafx.scene.Cursor.HAND);
        label.setOnMouseClicked(event -> action.run());
        return label;
    }

    private static String bytes(long value) {
        if (value >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.1f MB", value / 1_048_576.0);
        }
        if (value >= 1024) {
            return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
        }
        return value + " B";
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private void loadLogs(DockerContainer container) {
        consoleOut.getChildren().clear();
        appendConsole("READING LAST 200 LINES…", "prompt-meta");
        worker.execute(() -> {
            CommandEntry entry = docker.logs(container.id(), 200);
            Platform.runLater(() -> {
                if (selected == null || !selected.id().equals(container.id()) || console != Console.LOGS) {
                    return;
                }
                consoleOut.getChildren().clear();
                String text = entry.output().isBlank() ? entry.error() : entry.output();
                appendConsole(text.isBlank() ? "NO LOGS." : text.stripTrailing(),
                        text.isBlank() ? "prompt-meta" : "prompt-output");
            });
        });
    }

    private void runExec() {
        String command = execInput.getText().strip();
        if (command.isEmpty() || selected == null) {
            return;
        }
        DockerContainer container = selected;
        execInput.clear();
        appendConsole(CLOCK.format(Instant.now()) + "  " + container.name() + " $ " + command, "prompt-command");
        execInput.setDisable(true);
        worker.execute(() -> {
            CommandEntry entry = docker.exec(container.id(), command);
            Platform.runLater(() -> {
                execInput.setDisable(false);
                if (!entry.output().isEmpty()) {
                    appendConsole(entry.output().stripTrailing(), "prompt-output");
                }
                if (!entry.error().isBlank()) {
                    appendConsole(entry.error().stripTrailing(), "prompt-error");
                }
                appendConsole("exit " + entry.exitCode() + "  ·  " + entry.duration().toMillis() + " ms", "prompt-meta");
                execInput.requestFocus();
            });
        });
    }

    private void appendConsole(String text, String styleClass) {
        Label block = new Label(text);
        block.getStyleClass().add(styleClass);
        block.setWrapText(true);
        block.setMinHeight(Region.USE_PREF_SIZE);
        consoleOut.getChildren().add(block);
        while (consoleOut.getChildren().size() > 400) {
            consoleOut.getChildren().remove(0);
        }
        Platform.runLater(() -> {
            consoleScroll.layout();
            consoleScroll.setVvalue(1);
        });
    }

    // Root

    private void runWithRoot(Runnable action) {
        if (docker != null && docker.needsRoot() && privilege.needsPassword()) {
            SudoPrompt prompt = new SudoPrompt(privilege,
                    (command, done) -> worker.execute(() -> {
                        CommandEntry entry = docker.verify(command);
                        Platform.runLater(() -> done.accept(entry.success()));
                    }),
                    () -> {
                        hideOverlay();
                        action.run();
                    },
                    this::hideOverlay);
            showOverlay(prompt);
        } else {
            action.run();
        }
    }

    private void showOverlay(Region card) {
        hideOverlay();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        overlay = new StackPane(scrim, card);
        overlay.setMinSize(0, 0);
        getChildren().add(overlay);
    }

    private void hideOverlay() {
        if (overlay != null) {
            overlay.getChildren().forEach(node -> {
                if (node instanceof RunDialog dialog) {
                    dialog.stopAnimation();
                }
            });
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    private void notice(String text, boolean error) {
        notice.setText(text);
        notice.getStyleClass().setAll("label", "files-notice");
        if (error) {
            notice.getStyleClass().add("files-notice-error");
        }
        notice.setVisible(true);
        noticeTimer.playFromStart();
    }

    private void switchView(View next) {
        view = next;
        cluster.setVisible(next == View.CONTAINERS);
        imagesView.setVisible(next == View.IMAGES);
        networksView.setVisible(next == View.NETWORKS);
        containersTab.getStyleClass().remove("mode-toggle-active");
        imagesTab.getStyleClass().remove("mode-toggle-active");
        networksTab.getStyleClass().remove("mode-toggle-active");
        (next == View.CONTAINERS ? containersTab : next == View.IMAGES ? imagesTab : networksTab)
                .getStyleClass().add("mode-toggle-active");
        refresh();
    }

    // Building

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        Label kicker = label("DOCKER.SCOPE", "series");
        Label versionLabel = label(status.usable() ? "ENGINE v" + status.version()
                + (docker != null && docker.needsRoot() ? "  ·  VIA ROOT" : "") : "DOCKER UNAVAILABLE", "header-note");

        containersTab.getStyleClass().add("mode-toggle-active");
        containersTab.setOnAction(event -> switchView(View.CONTAINERS));
        imagesTab.setOnAction(event -> switchView(View.IMAGES));
        networksTab.setOnAction(event -> switchView(View.NETWORKS));
        HBox tabs = new HBox(6, containersTab, imagesTab, networksTab);
        tabs.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button run = new Button("＋ RUN");
        run.getStyleClass().add("ghost-button");
        run.setOnAction(event -> showRun());
        Button free = new Button("⌁ FREE SPACE");
        free.getStyleClass().add("ghost-button");
        free.setOnAction(event -> prune());
        Button refresh = new Button("↻ REFRESH");
        refresh.getStyleClass().add("ghost-button");
        refresh.setOnAction(event -> refresh());
        Button help = new Button("? HELP");
        help.getStyleClass().add("ghost-button");
        help.setOnAction(event -> showOverlay(new DockerHelp(this::hideOverlay)));

        HBox header = new HBox(16, brand, kicker, versionLabel, tabs, spacer, rootSlot, run, free, refresh, help);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(22, 40, 10, 36));
        HBox.setMargin(tabs, new Insets(0, 0, 0, 14));
        return header;
    }

    private VBox buildDetails() {
        commandValue.setWrapText(true);
        commandValue.setMinHeight(Region.USE_PREF_SIZE);
        nameLabel.setWrapText(true);
        healthBadge.setVisible(false);
        healthBadge.setManaged(false);
        HBox nameRow = new HBox(10, nameLabel, healthBadge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(9);
        String[] names = {"IMAGE", "STATE", "RESTART"};
        Label[] values = {imageValue, stateValue, restartValue};
        for (int i = 0; i < names.length; i++) {
            grid.add(label(names[i], "metric-label"), 0, i);
            grid.add(values[i], 1, i);
        }

        startButton.setOnAction(event -> act("START", docker::start));
        stopButton.setOnAction(event -> act("STOP", docker::stop));
        restartButton.setOnAction(event -> act("RESTART", docker::restart));
        deleteButton.setOnAction(event -> delete());
        restartStackButton.setOnAction(event -> {
            if (selected != null) {
                restartStack(selected.project());
            }
        });
        restartStackButton.setVisible(false);
        restartStackButton.setManaged(false);
        HBox actionRow = new HBox(10, startButton, stopButton, restartButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        HBox deleteRow = new HBox(12, deleteButton, restartStackButton);
        deleteRow.setAlignment(Pos.CENTER_LEFT);
        actionStatus.setWrapText(true);
        actionStatus.setMinHeight(Region.USE_PREF_SIZE);

        VBox column = new VBox(6,
                label("CONTAINER", "series"), nameRow, idLabel, grid,
                label("COMMAND", "metric-label"), commandValue,
                label("ACTIONS", "series"), actionRow, deleteRow, actionStatus,
                label("PORTS", "series"), portsBox,
                label("VOLUMES", "series"), mountsBox,
                label("ENVIRONMENT", "series"), envBox);
        VBox.setMargin(nameRow, new Insets(10, 0, 0, 0));
        VBox.setMargin(grid, new Insets(14, 0, 4, 0));
        marginBefore(column, "ACTIONS", 20);
        VBox.setMargin(actionRow, new Insets(4, 0, 0, 0));
        marginBefore(column, "PORTS", 22);
        marginBefore(column, "VOLUMES", 20);
        marginBefore(column, "ENVIRONMENT", 20);

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        column.setPadding(new Insets(20, 34, 24, 14));
        VBox holder = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        holder.setPrefWidth(346);
        holder.setMinWidth(300);
        return holder;
    }

    private static void marginBefore(VBox column, String seriesText, double top) {
        for (javafx.scene.Node node : column.getChildren()) {
            if (node instanceof Label label && label.getText().equals(seriesText)
                    && label.getStyleClass().contains("series")) {
                VBox.setMargin(node, new Insets(top, 0, 4, 0));
                return;
            }
        }
    }

    private VBox buildConsole() {
        logsTab.getStyleClass().add("mode-toggle-active");
        logsTab.setOnAction(event -> setConsoleMode(Console.LOGS));
        shellTab.setOnAction(event -> setConsoleMode(Console.SHELL));
        filesTab.setOnAction(event -> setConsoleMode(Console.FILES));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = label("LOGS = LAST 200 LINES (REFRESH TO UPDATE)  ·  SHELL RUNS ONE COMMAND INSIDE THE "
                + "CONTAINER  ·  EVERY COMMAND IS LISTED IN THE LOGS", "metric-label");
        HBox tabs = new HBox(10, consoleTitle, logsTab, shellTab, filesTab, spacer, hint);
        tabs.setAlignment(Pos.CENTER_LEFT);

        consoleScroll.setFitToWidth(true);
        consoleScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(consoleScroll, Priority.ALWAYS);

        Label sign = label("$", "prompt-sign");
        execInput.getStyleClass().add("prompt-input");
        execInput.setPromptText("run a command inside the container");
        execInput.setOnAction(event -> runExec());
        HBox.setHgrow(execInput, Priority.ALWAYS);
        execRow = new HBox(10, sign, execInput);
        execRow.setAlignment(Pos.CENTER_LEFT);
        execRow.setVisible(false);
        execRow.setManaged(false);

        VBox console = new VBox(8, tabs, consoleScroll, execRow);
        console.getStyleClass().add("prompt-panel");
        console.setPadding(new Insets(12, 40, 14, 36));
        console.setPrefHeight(220);
        console.setMinHeight(150);
        return console;
    }

    private static Button tab(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("mode-toggle");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Button actionButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("primary-button") || styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-button");
        }
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static String problem(CommandEntry entry) {
        String error = entry.error().strip();
        if (!error.isEmpty()) {
            return error.lines().findFirst().orElse("").toUpperCase(Locale.ROOT);
        }
        return entry.ran() ? "FAILED WITH EXIT CODE " + entry.exitCode() : "COULD NOT RUN";
    }

    private static String clip(String text) {
        return text.length() > 42 ? "…" + text.substring(text.length() - 41) : text;
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // Previews reach in through these.

    DockerCluster cluster() {
        return cluster;
    }

    void selectForPreview(DockerContainer container, ContainerDetails details) {
        selected = container;
        cluster.select(container.id());
        nameLabel.setText(container.name());
        idLabel.setText(container.shortId() + (container.inStack() ? "  ·  " + container.project() : ""));
        imageValue.setText(container.image());
        updateSelected(container);
        commandValue.setText(details.command());
        restartValue.setText(details.restartPolicy());
        fillPorts(details.ports());
        fillMounts(details.mounts());
        fillEnv(details.env());
    }

    /**
     * Shows a container listing without a server, for previews.
     */
    void showFilesForPreview(String path, ContainerFiles.Listing listing) {
        setConsoleMode(Console.FILES);
        containerPath = path;
        showListing(selected, path, listing);
    }

    void showLogsForPreview(String text) {
        consoleOut.getChildren().clear();
        appendConsole(text, "prompt-output");
    }

    void showImagesForPreview(List<com.kubo.model.docker.DockerImage> images) {
        switchViewForPreview(View.IMAGES);
        imagesView.setImages(images);
    }

    void showNetworksForPreview(List<com.kubo.model.docker.DockerNetwork> networks) {
        switchViewForPreview(View.NETWORKS);
        networksView.setNetworks(networks);
    }

    private void switchViewForPreview(View next) {
        view = next;
        cluster.setVisible(next == View.CONTAINERS);
        imagesView.setVisible(next == View.IMAGES);
        networksView.setVisible(next == View.NETWORKS);
    }
}
