package com.kubo.ui.logs;

import com.kubo.command.CommandService;
import com.kubo.logs.CommandGroup;
import com.kubo.logs.LogExport;
import com.kubo.logs.SessionHistory;
import com.kubo.logs.SessionHistory.Command;
import com.kubo.ui.ListSelect;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every session log on this computer: the sessions on the left, the commands of the chosen one on the right.
 *
 * <p>Each screen's commands apart: a pill per screen says how many it sent and shows only those. The same
 * commands can be read in the order they ran, or by screen with each command once and how often it ran. Rows
 * can be selected and copied, and the whole session downloaded as a structured JSON file. The current session
 * keeps growing live.
 */
public final class LogsView extends BorderPane {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final Map<String, String> ORIGIN_STYLE = Map.of(
            "MONITOR", "log-origin-monitor", "FILES", "log-origin-files", "USER", "log-origin-user",
            "DOCKER", "log-origin-docker", "REDIS", "log-origin-redis", "NETWORK", "log-origin-network",
            "PACKAGES", "log-origin-packages", "SERVICES", "log-origin-services");

    /** Which commands, by who sent them. */
    private enum Kind {
        EVERY("EVERY COMMAND", command -> true),
        KUBO("SENT BY KUBO", command -> !command.byHand()),
        TYPED("TYPED", Command::byHand),
        ROOT("AS ROOT", Command::asRoot);

        final String label;
        final Predicate<Command> keeps;

        Kind(String label, Predicate<Command> keeps) {
            this.label = label;
            this.keeps = keeps;
        }
    }

    private final Path directory;
    private final Path current;
    private final CommandService commands;
    private final ListView<Path> sessions = new ListView<>();
    private final ObservableList<Command> entries = FXCollections.observableArrayList();
    private final FilteredList<Command> shown = new FilteredList<>(entries);
    private final ListView<Command> list = new ListView<>(shown);
    private final ListView<CommandGroup> grouped = new ListView<>();
    private final Label titleLabel = label("—", "files-title");
    private final Label detailLabel = label("", "mono-muted");
    private final Label countLabel = label("", "metric-label");
    private final Label feedback = label("", "log-live");
    private final PauseTransition feedbackFades = new PauseTransition(javafx.util.Duration.seconds(4));
    private final ToggleGroup originGroup = new ToggleGroup();
    private final ToggleButton all = toggle("ALL");
    private final Map<CommandService.Origin, ToggleButton> origins = new EnumMap<>(CommandService.Origin.class);
    private final ToggleGroup kindGroup = new ToggleGroup();
    private final Map<Kind, ToggleButton> kinds = new EnumMap<>(Kind.class);
    private final ToggleGroup viewGroup = new ToggleGroup();
    private final ToggleButton timeline = toggle("IN ORDER");
    private final ToggleButton byService = toggle("BY SERVICE");
    private final ToggleButton failures = toggle("FAILURES ONLY");
    private final TextField search = new TextField();
    private final Button export = new Button("EXPORT JSON");

    /** The open session, for the export. */
    private Path openPath;
    private String openTarget = "";
    private Instant openStarted = Instant.EPOCH;
    private Runnable stopListening = () -> {
    };
    /** Where the save card goes while a file is being written. */
    private final javafx.scene.layout.StackPane saveOverlay = new javafx.scene.layout.StackPane();
    /** Writing happens here rather than on the interface thread. */
    private final java.util.concurrent.ExecutorService saving =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kubo-logs-save");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * @param current  the log of the running session, or null
     * @param commands the running session's commands, to follow it live; null for none
     */
    public LogsView(Path directory, Path current, CommandService commands) {
        this.directory = directory;
        this.current = current;
        this.commands = commands;
        getStyleClass().add("files-view");
        feedbackFades.setOnFinished(event -> feedback.setText(""));

        setTop(buildHeader());
        setLeft(buildSessions());
        saveOverlay.setVisible(false);
        saveOverlay.setManaged(false);
        saveOverlay.setPickOnBounds(true);
        setCenter(new javafx.scene.layout.StackPane(buildCommands(), saveOverlay));

        refreshSessions();
    }

    public void shutdown() {
        stopListening.run();
        saving.shutdownNow();
    }

    /**
     * Shows commands without reading a file; for previews.
     */
    void showSample(String target, Instant startedAt, List<Command> sample) {
        titleLabel.setText(target);
        detailLabel.setText("STARTED " + DAY.format(startedAt) + "  ·  LIVE");
        openTarget = target;
        openStarted = startedAt;
        entries.setAll(sample);
        applyFilter();
    }

    /** For previews: the grouped view, one screen picked, or null for all of them. */
    void showByServiceForPreview(CommandService.Origin origin) {
        byService.setSelected(true);
        (origin == null ? all : origins.get(origin)).setSelected(true);
        applyFilter();
    }

    /** For previews: some rows selected, as a reader about to copy them would have them. */
    void selectForPreview(int from, int to) {
        list.getSelectionModel().selectRange(from, to);
    }

    private void refreshSessions() {
        try {
            sessions.getItems().setAll(SessionHistory.files(directory));
        } catch (IOException e) {
            sessions.getItems().clear();
            detailLabel.setText("CANNOT READ " + directory + ": " + e.getMessage());
        }
        if (!sessions.getItems().isEmpty()) {
            Path first = current != null && sessions.getItems().contains(current) ? current : sessions.getItems().get(0);
            sessions.getSelectionModel().select(first);
        }
    }

    private void open(Path path) {
        stopListening.run();
        stopListening = () -> {
        };
        if (path == null) {
            return;
        }
        try {
            SessionHistory.Session session = SessionHistory.read(path);
            boolean live = path.equals(current);
            openPath = path;
            openTarget = session.target();
            openStarted = session.startedAt();
            titleLabel.setText(session.target());
            detailLabel.setText("STARTED " + DAY.format(session.startedAt()) + (live ? "  ·  LIVE" : "")
                    + "  ·  " + path.getFileName());
            entries.setAll(session.commands());
            if (live && commands != null) {
                stopListening = commands.addLoggedListener(logged -> Platform.runLater(() -> {
                    boolean following = atTheEnd();
                    entries.add(Command.of(logged.entry(), logged.origin(), logged.typed()));
                    applyFilter();
                    if (following && list.getSelectionModel().isEmpty()) {
                        list.scrollTo(Math.max(0, shown.size() - 1));
                    }
                }));
            }
        } catch (IOException e) {
            openPath = null;
            entries.clear();
            titleLabel.setText("UNREADABLE LOG");
            detailLabel.setText(e.getMessage());
        }
        applyFilter();
        Platform.runLater(() -> list.scrollTo(Math.max(0, shown.size() - 1)));
    }

    /**
     * Whether the last entry is on the screen, which is how "was the reader following the log" is asked of a
     * list view: it does not say where it is scrolled, only which rows it has been asked to build.
     */
    private boolean atTheEnd() {
        if (shown.isEmpty()) {
            return true;
        }
        javafx.scene.Node skin = list.lookup(".virtual-flow");
        if (!(skin instanceof javafx.scene.control.skin.VirtualFlow<?> flow)) {
            return true;
        }
        javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
        return last == null || last.getIndex() >= shown.size() - 2;
    }

    private String selectedOrigin() {
        for (Map.Entry<CommandService.Origin, ToggleButton> entry : origins.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey().name();
            }
        }
        return null;
    }

    private Kind selectedKind() {
        for (Map.Entry<Kind, ToggleButton> entry : kinds.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return Kind.EVERY;
    }

    private void applyFilter() {
        String origin = selectedOrigin();
        Kind kind = selectedKind();
        String text = search.getText().strip().toLowerCase(Locale.ROOT);
        boolean onlyFailures = failures.isSelected();
        shown.setPredicate(command -> (origin == null || command.origin().equals(origin))
                && kind.keeps.test(command)
                && (!onlyFailures || !command.success())
                && (text.isEmpty() || command.command().toLowerCase(Locale.ROOT).contains(text)
                || command.error().toLowerCase(Locale.ROOT).contains(text)));

        // How many each screen sent, on its pill. A screen that sent nothing this session has no pill, unless it
        // is the one picked.
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Command command : entries) {
            counts.merge(command.origin(), 1L, Long::sum);
        }
        all.setText("ALL  " + entries.size());
        origins.forEach((each, pill) -> {
            long count = counts.getOrDefault(each.name(), 0L);
            pill.setText(name(each.name()) + "  " + count);
            pill.setVisible(count > 0 || pill.isSelected());
            pill.setManaged(pill.isVisible());
        });

        boolean groupedView = byService.isSelected();
        if (groupedView) {
            grouped.getItems().setAll(CommandGroup.of(List.copyOf(shown)));
        }
        grouped.setVisible(groupedView);
        list.setVisible(!groupedView);

        long failed = entries.stream().filter(command -> !command.success()).count();
        countLabel.setText((groupedView ? grouped.getItems().size() + " DISTINCT  ·  " : "")
                + shown.size() + " SHOWN  ·  " + entries.size() + " COMMANDS  ·  " + failed + " FAILED");
    }

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        VBox names = new VBox(2, titleLabel, detailLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        export.getStyleClass().add("ghost-button");
        export.setOnAction(event -> export());
        Button reveal = new Button("OPEN FOLDER");
        reveal.getStyleClass().add("ghost-button");
        reveal.setOnAction(event -> reveal());
        Button reload = new Button("READ AGAIN");
        reload.getStyleClass().add("ghost-button");
        reload.setOnAction(event -> refreshSessions());

        HBox header = new HBox(20, brand, label("LOGS.SCOPE", "series"), names, spacer, export, reload, reveal);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(22, 40, 14, 36));
        return header;
    }

    private VBox buildSessions() {
        sessions.getStyleClass().add("logs-list");
        sessions.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String name = path.getFileName().toString().replaceFirst("\\.json$", "");
                String when = name.length() >= 19 ? name.substring(0, 10) + "  " + name.substring(11, 19).replace('-', ':') : name;
                String target = name.length() > 20 ? name.substring(20) : "";
                Label time = label(when, "mono");
                Label who = label(target, "mono-muted");
                VBox box = new VBox(2, time, who);
                if (path.equals(current)) {
                    box.getChildren().add(label("LIVE  ·  THIS SESSION", "log-live"));
                }
                setText(null);
                setGraphic(box);
            }
        });
        sessions.getSelectionModel().selectedItemProperty().addListener((observable, previous, path) -> open(path));
        VBox.setVgrow(sessions, Priority.ALWAYS);
        VBox column = new VBox(10, label("SESSIONS", "series"), sessions);
        column.setPadding(new Insets(0, 12, 20, 36));
        column.setPrefWidth(270);
        return column;
    }

    private VBox buildCommands() {
        // Who sent it: one pill per screen.
        all.setToggleGroup(originGroup);
        FlowPane services = new FlowPane(14, 8);
        services.getChildren().add(all);
        for (CommandService.Origin origin : CommandService.Origin.values()) {
            ToggleButton pill = toggle(name(origin.name()));
            pill.setToggleGroup(originGroup);
            origins.put(origin, pill);
            services.getChildren().add(pill);
        }
        all.setSelected(true);
        keepOneSelected(originGroup);

        // How: in order or grouped, by Kubo or typed, and failures.
        timeline.setToggleGroup(viewGroup);
        byService.setToggleGroup(viewGroup);
        timeline.setSelected(true);
        keepOneSelected(viewGroup);
        HBox types = new HBox(14);
        types.setAlignment(Pos.CENTER_LEFT);
        for (Kind kind : Kind.values()) {
            ToggleButton pill = toggle(kind.label);
            pill.setToggleGroup(kindGroup);
            kinds.put(kind, pill);
            types.getChildren().add(pill);
        }
        kinds.get(Kind.EVERY).setSelected(true);
        keepOneSelected(kindGroup);
        failures.selectedProperty().addListener((observable, previous, selected) -> applyFilter());
        search.setPromptText("search commands and errors");
        search.getStyleClass().add("find-field");
        search.textProperty().addListener((observable, previous, text) -> applyFilter());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox views = new HBox(14, timeline, byService);
        views.setAlignment(Pos.CENTER_LEFT);
        search.setPrefWidth(240);
        search.setMinWidth(160);
        HBox filters = new HBox(28, views, types, failures, spacer, search);
        filters.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("logs-list");
        list.setCellFactory(view -> new CommandCell(view));
        ListSelect.install(list, count -> say("COPIED " + rows(count)),
                new ListSelect.Copy<>("COPY WITH TIME AND SERVICE", LogsView::timelineText),
                new ListSelect.Copy<>("COPY THE COMMANDS ONLY", LogsView::commandsOnly));
        grouped.getStyleClass().add("logs-list");
        grouped.setCellFactory(view -> new GroupCell(view));
        ListSelect.install(grouped, count -> say("COPIED " + rows(count)),
                new ListSelect.Copy<>("COPY WITH COUNTS AND SERVICE", LogsView::groupText),
                new ListSelect.Copy<>("COPY THE COMMANDS ONLY", LogsView::groupCommandsOnly));
        grouped.setVisible(false);
        StackPane lists = new StackPane(list, grouped);
        VBox.setVgrow(lists, Priority.ALWAYS);

        Label note = label("OUTPUTS ARE NEVER STORED, ONLY THEIR SIZE  ·  FILE CONTENTS SENT BY SAVES AND UPLOADS ARE NOT "
                + "PART OF THE COMMANDS  ·  CLICK, SHIFT-CLICK OR DRAG TO SELECT, CTRL+C COPIES", "metric-label");
        note.setMinWidth(0);
        Region noteSpacer = new Region();
        HBox.setHgrow(noteSpacer, Priority.ALWAYS);
        HBox foot = new HBox(12, note, noteSpacer, feedback);
        foot.setAlignment(Pos.CENTER_LEFT);
        // The counts on the line of the screens they count, where there is room for them.
        countLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(services, Priority.ALWAYS);
        HBox top = new HBox(20, services, countLabel);
        top.setAlignment(Pos.TOP_LEFT);
        VBox column = new VBox(12, top, filters, lists, foot);
        column.setPadding(new Insets(0, 40, 20, 12));
        return column;
    }

    private void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            applyFilter();
        });
    }

    /** The whole open session as a structured JSON file, wherever the reader wants it. */
    /**
     * Writes the open session wherever the operator wants it, and says so.
     *
     * <p>Through the shared save card, the same as every other export: a file written in silence is a file
     * nobody is sure about, and on a slow disk it looks exactly like one that failed.
     */
    private void export() {
        if (openTarget.isEmpty() && entries.isEmpty()) {
            say("NO SESSION IS OPEN");
            return;
        }
        SessionHistory.Session session = new SessionHistory.Session(openPath, openTarget, openStarted,
                List.copyOf(entries));
        String base = openPath == null ? "kubo-session"
                : openPath.getFileName().toString().replaceFirst("\\.json$", "");
        com.kubo.ui.SaveCard card = com.kubo.ui.SaveCard.ask(
                getScene() == null ? null : getScene().getWindow(),
                "THIS SESSION", base + "-export.json",
                file -> LogExport.write(session, file, Instant.now()),
                saving, this::hideSaving);
        if (card == null) {
            // Cancelling the chooser is an answer, not an error.
            return;
        }
        showSaving(card);
    }

    /** The save card sits over the whole view while it writes. */
    private void showSaving(javafx.scene.Node card) {
        javafx.scene.layout.StackPane over = new javafx.scene.layout.StackPane(card);
        over.getStyleClass().add("overlay-dim");
        saveOverlay.getChildren().setAll(over);
        saveOverlay.setVisible(true);
        saveOverlay.setManaged(true);
    }

    private void hideSaving() {
        saveOverlay.getChildren().clear();
        saveOverlay.setVisible(false);
        saveOverlay.setManaged(false);
    }

    private void say(String text) {
        feedback.setText(text);
        feedbackFades.playFromStart();
    }

    private void reveal() {
        try {
            Files.createDirectories(directory);
            com.kubo.platform.Host.openFolder(directory);
        } catch (IOException e) {
            detailLabel.setText("CANNOT OPEN " + directory + ": " + e.getMessage());
        }
    }

    // What is copied

    private static String timelineText(List<Command> rows) {
        StringBuilder text = new StringBuilder();
        for (Command command : rows) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(STAMP.format(command.at())).append("  ").append(name(command.origin()))
                    .append("  ").append(status(command)).append("  ").append(command.durationMs()).append(" ms");
            if (command.asRoot()) {
                text.append("  root");
            }
            if (command.byHand()) {
                text.append("  typed");
            }
            text.append("  ").append(command.command());
        }
        return text.toString();
    }

    private static String commandsOnly(List<Command> rows) {
        return String.join("\n", rows.stream().map(Command::command).toList());
    }

    private static String groupText(List<CommandGroup> rows) {
        StringBuilder text = new StringBuilder();
        for (CommandGroup group : rows) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(name(group.origin())).append("  x").append(group.count())
                    .append("  first ").append(STAMP.format(group.first()))
                    .append("  last ").append(STAMP.format(group.last()))
                    .append("  avg ").append(group.averageMs()).append(" ms");
            if (group.failed() > 0) {
                text.append("  ").append(group.failed()).append(" failed");
            }
            text.append("  ").append(group.command());
        }
        return text.toString();
    }

    private static String groupCommandsOnly(List<CommandGroup> rows) {
        return String.join("\n", rows.stream().map(CommandGroup::command).toList());
    }

    private static String status(Command command) {
        return command.success() ? "ok" : command.ran() ? "exit " + command.exitCode() : "not run";
    }

    private static String rows(int count) {
        return count == 1 ? "1 ROW" : count + " ROWS";
    }

    /** What a screen is called on its pill: USER was the name of typed commands before they had a flag. */
    private static String name(String origin) {
        return origin.equals("USER") ? "SHELL" : origin;
    }

    private static Label originChip(String origin) {
        Label chip = label(name(origin), "log-origin");
        chip.getStyleClass().add(ORIGIN_STYLE.getOrDefault(origin, "log-origin-monitor"));
        chip.setMinWidth(72);
        return chip;
    }

    private static final class CommandCell extends ListCell<Command> {

        private final ListView<Command> view;

        CommandCell(ListView<Command> view) {
            this.view = view;
            ListSelect.dragAcross(this);
        }

        @Override
        protected void updateItem(Command command, boolean empty) {
            super.updateItem(command, empty);
            setText(null);
            if (empty || command == null) {
                setGraphic(null);
                return;
            }
            String status = command.success() ? "ok" : command.ran() ? "exit " + command.exitCode() : "fail";
            Label time = label(CLOCK.format(command.at()), "mono-muted");
            Label origin = originChip(command.origin());
            Label state = label(status, command.success() ? "log-ok" : command.ran() ? "log-failed" : "log-error");
            Label duration = label(command.durationMs() + " ms", "mono-muted");
            for (Label fixed : new Label[]{time, state, duration}) {
                fixed.setMinWidth(Region.USE_PREF_SIZE);
            }
            state.setMinWidth(46);
            duration.setMinWidth(62);
            HBox meta = new HBox(12, time, origin, state, duration);
            meta.setAlignment(Pos.CENTER_LEFT);
            if (command.asRoot()) {
                meta.getChildren().add(label("ROOT", "log-mark-root"));
            }
            if (command.byHand()) {
                meta.getChildren().add(label("TYPED", "log-mark-typed"));
            }

            Label text = label(command.command(), "log-command");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            text.maxWidthProperty().bind(view.widthProperty().subtract(60));
            VBox box = new VBox(3, meta, text);
            if (!command.error().isEmpty() && !command.success()) {
                Label error = label(command.error(), "prompt-error");
                error.setWrapText(true);
                error.setMinHeight(Region.USE_PREF_SIZE);
                error.maxWidthProperty().bind(view.widthProperty().subtract(60));
                box.getChildren().add(error);
            }
            setGraphic(box);
        }
    }

    /** One command of one screen, run this many times; the first of a screen carries the screen's heading. */
    private static final class GroupCell extends ListCell<CommandGroup> {

        private final ListView<CommandGroup> view;

        GroupCell(ListView<CommandGroup> view) {
            this.view = view;
            ListSelect.dragAcross(this);
        }

        @Override
        protected void updateItem(CommandGroup group, boolean empty) {
            super.updateItem(group, empty);
            setText(null);
            if (empty || group == null) {
                setGraphic(null);
                return;
            }
            VBox box = new VBox(4);
            int index = getIndex();
            List<CommandGroup> items = view.getItems();
            if (index == 0 || !items.get(index - 1).origin().equals(group.origin())) {
                // The heading of a screen: how much it sent, and how many different things.
                long total = 0;
                long distinct = 0;
                for (CommandGroup each : items) {
                    if (each.origin().equals(group.origin())) {
                        total += each.count();
                        distinct++;
                    }
                }
                Label heading = label(total + (total == 1 ? " COMMAND" : " COMMANDS") + "  ·  " + distinct
                        + " DIFFERENT", "metric-label");
                HBox top = new HBox(12, originChip(group.origin()), heading);
                top.setAlignment(Pos.CENTER_LEFT);
                top.setPadding(new Insets(index == 0 ? 0 : 10, 0, 4, 0));
                box.getChildren().add(top);
            }

            Label count = label("×" + group.count(), "log-count");
            count.setMinWidth(54);
            Label when = label(group.count() == 1 ? CLOCK.format(group.first())
                    : CLOCK.format(group.first()) + " → " + CLOCK.format(group.last()), "mono-muted");
            when.setMinWidth(Region.USE_PREF_SIZE);
            Label average = label("≈ " + group.averageMs() + " ms", "mono-muted");
            average.setMinWidth(Region.USE_PREF_SIZE);
            HBox meta = new HBox(12, count, when, average);
            meta.setAlignment(Pos.CENTER_LEFT);
            if (group.failed() + group.notRun() > 0) {
                meta.getChildren().add(label((group.failed() + group.notRun()) + " FAILED", "log-error"));
            }
            if (group.asRoot() > 0) {
                meta.getChildren().add(label("ROOT", "log-mark-root"));
            }
            if (group.typed() > 0) {
                meta.getChildren().add(label("TYPED", "log-mark-typed"));
            }
            Label text = label(group.command(), "log-command");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            text.maxWidthProperty().bind(view.widthProperty().subtract(60));
            box.getChildren().addAll(meta, text);
            setGraphic(box);
        }
    }

    private static ToggleButton toggle(String text) {
        Circle marker = new Circle(3);
        marker.getStyleClass().add("mode-marker");
        ToggleButton toggle = new ToggleButton(text, marker);
        toggle.getStyleClass().add("mode-toggle");
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        return toggle;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
