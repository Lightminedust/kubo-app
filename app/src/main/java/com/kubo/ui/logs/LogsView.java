package com.kubo.ui.logs;

import com.kubo.command.CommandService;
import com.kubo.logs.SessionHistory;
import com.kubo.logs.SessionHistory.Command;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every session log on this computer: the sessions on the left, the commands of the chosen one on the right,
 * filtered by origin, failures or text. The current session keeps growing live.
 */
public final class LogsView extends BorderPane {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Map<String, String> ORIGIN_STYLE = Map.of(
            "MONITOR", "log-origin-monitor", "FILES", "log-origin-files", "USER", "log-origin-user",
            "DOCKER", "log-origin-docker", "REDIS", "log-origin-redis");

    private final Path directory;
    private final Path current;
    private final CommandService commands;
    private final ListView<Path> sessions = new ListView<>();
    private final ObservableList<Command> entries = FXCollections.observableArrayList();
    private final FilteredList<Command> shown = new FilteredList<>(entries);
    private final ListView<Command> list = new ListView<>(shown);
    private final Label titleLabel = label("—", "files-title");
    private final Label detailLabel = label("", "mono-muted");
    private final Label countLabel = label("", "metric-label");
    private final ToggleGroup originGroup = new ToggleGroup();
    private final ToggleButton all = toggle("ALL");
    private final ToggleButton files = toggle("FILES");
    private final ToggleButton user = toggle("SHELL");
    private final ToggleButton monitor = toggle("MONITOR");
    private final ToggleButton docker = toggle("DOCKER");
    private final ToggleButton redis = toggle("REDIS");
    private final ToggleButton failures = toggle("FAILURES ONLY");
    private final TextField search = new TextField();
    private Runnable stopListening = () -> {
    };

    /**
     * @param current  the log of the running session, or null
     * @param commands the running session's commands, to follow it live; null for none
     */
    public LogsView(Path directory, Path current, CommandService commands) {
        this.directory = directory;
        this.current = current;
        this.commands = commands;
        getStyleClass().add("files-view");

        setTop(buildHeader());
        setLeft(buildSessions());
        setCenter(buildCommands());

        refreshSessions();
    }

    public void shutdown() {
        stopListening.run();
    }

    /**
     * Shows commands without reading a file; for previews.
     */
    void showSample(String target, Instant startedAt, List<Command> sample) {
        titleLabel.setText(target);
        detailLabel.setText("STARTED " + DAY.format(startedAt) + "  ·  LIVE");
        entries.setAll(sample);
        applyFilter();
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
            titleLabel.setText(session.target());
            detailLabel.setText("STARTED " + DAY.format(session.startedAt()) + (live ? "  ·  LIVE" : "")
                    + "  ·  " + path.getFileName());
            entries.setAll(session.commands());
            if (live && commands != null) {
                stopListening = commands.addListener((entry, origin) -> Platform.runLater(() -> {
                    entries.add(Command.of(entry, origin));
                    applyFilter();
                }));
            }
        } catch (IOException e) {
            entries.clear();
            titleLabel.setText("UNREADABLE LOG");
            detailLabel.setText(e.getMessage());
        }
        // Only follow the end when the reader was already there: a list that jumps back down on every new
        // entry is a list nobody can look through.
        boolean following = atTheEnd();
        applyFilter();
        if (following) {
            Platform.runLater(() -> list.scrollTo(Math.max(0, shown.size() - 1)));
        }
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

    private void applyFilter() {
        String origin = originGroup.getSelectedToggle() == files ? "FILES"
                : originGroup.getSelectedToggle() == user ? "USER"
                : originGroup.getSelectedToggle() == monitor ? "MONITOR"
                : originGroup.getSelectedToggle() == docker ? "DOCKER"
                : originGroup.getSelectedToggle() == redis ? "REDIS" : null;
        String text = search.getText().strip().toLowerCase(Locale.ROOT);
        boolean onlyFailures = failures.isSelected();
        shown.setPredicate(command -> (origin == null || command.origin().equals(origin))
                && (!onlyFailures || !command.success())
                && (text.isEmpty() || command.command().toLowerCase(Locale.ROOT).contains(text)
                || command.error().toLowerCase(Locale.ROOT).contains(text)));
        long failed = entries.stream().filter(command -> !command.success()).count();
        countLabel.setText(shown.size() + " SHOWN  ·  " + entries.size() + " COMMANDS  ·  " + failed + " FAILED");
    }

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        VBox names = new VBox(2, titleLabel, detailLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button reveal = new Button("OPEN FOLDER");
        reveal.getStyleClass().add("ghost-button");
        reveal.setOnAction(event -> reveal());
        Button reload = new Button("READ AGAIN");
        reload.getStyleClass().add("ghost-button");
        reload.setOnAction(event -> refreshSessions());

        HBox header = new HBox(20, brand, label("LOGS.SCOPE", "series"), names, spacer, reload, reveal);
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
        for (ToggleButton toggle : new ToggleButton[]{all, files, user, monitor, docker, redis}) {
            toggle.setToggleGroup(originGroup);
        }
        all.setSelected(true);
        originGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            applyFilter();
        });
        failures.selectedProperty().addListener((observable, previous, selected) -> applyFilter());
        search.setPromptText("search commands and errors");
        search.getStyleClass().add("find-field");
        search.textProperty().addListener((observable, previous, text) -> applyFilter());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox filters = new HBox(18, all, files, user, monitor, docker, redis, failures, spacer, countLabel, search);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(failures, new Insets(0, 0, 0, 14));

        list.getStyleClass().add("logs-list");
        list.setCellFactory(view -> new CommandCell(view));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label note = label("OUTPUTS ARE NEVER STORED, ONLY THEIR SIZE  ·  FILE CONTENTS SENT BY SAVES AND UPLOADS ARE NOT "
                + "PART OF THE COMMANDS", "metric-label");
        VBox column = new VBox(12, filters, list, note);
        column.setPadding(new Insets(0, 40, 20, 12));
        return column;
    }

    private void reveal() {
        try {
            Files.createDirectories(directory);
            com.kubo.platform.Host.openFolder(directory);
        } catch (IOException e) {
            detailLabel.setText("CANNOT OPEN " + directory + ": " + e.getMessage());
        }
    }

    private static final class CommandCell extends ListCell<Command> {

        private final ListView<Command> view;

        CommandCell(ListView<Command> view) {
            this.view = view;
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
            Label origin = label(command.origin().equals("USER") ? "SHELL" : command.origin(), "log-origin");
            origin.getStyleClass().add(ORIGIN_STYLE.getOrDefault(command.origin(), "log-origin-monitor"));
            Label state = label(status, command.success() ? "log-ok" : command.ran() ? "log-failed" : "log-error");
            Label duration = label(command.durationMs() + " ms", "mono-muted");
            for (Label fixed : new Label[]{time, origin, state, duration}) {
                fixed.setMinWidth(Region.USE_PREF_SIZE);
            }
            origin.setMinWidth(64);
            state.setMinWidth(46);
            duration.setMinWidth(62);
            HBox meta = new HBox(12, time, origin, state, duration);
            meta.setAlignment(Pos.CENTER_LEFT);

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
