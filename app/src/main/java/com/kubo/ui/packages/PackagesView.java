package com.kubo.ui.packages;

import com.kubo.command.CommandEntry;
import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageRole;
import com.kubo.model.packages.PackageInventory;
import com.kubo.packages.AptCommands;
import com.kubo.packages.AptService;
import com.kubo.privilege.RootSession;
import com.kubo.ui.PromptCard;
import com.kubo.ui.docker.SudoPrompt;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What is installed on the server and what to do about it: the map in the middle, whatever you opened on it
 * listed on the right, and the actions that change things — each one shown as a simulation before it runs.
 */
public final class PackagesView extends StackPane {

    private static final Color ALERT = PackagePalette.ALERT;
    private static final Color AMBER = PackagePalette.AMBER;
    private static final Color LIVE = PackagePalette.LIVE;

    private final AptService apt;
    private final RootSession root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-packages");
        thread.setDaemon(true);
        return thread;
    });

    private final PackageTree tree = new PackageTree();
    private final Label headline = label("PACKAGES", "sheet-title");
    private final Label caption = label("", "sheet-caption");
    private final HBox tagRow = new HBox(6);
    private final Label installedValue = label("—", "readout-value");
    private final Label waitingValue = label("—", "readout-value");
    private final Label securityValue = label("—", "readout-value");

    private final Label listTitle = label("EVERYTHING", "series");
    private final Label listNote = label("", "metric-label");
    private final Label listCount = label("", "metric-label");
    private final ToggleButton askedForOnly = new ToggleButton("ONLY WHAT WAS ASKED FOR");
    private final TextField filterField = new TextField();
    private final ListView<PackageEntry> list = new ListView<>();
    private final javafx.collections.ObservableList<PackageEntry> shown =
            javafx.collections.FXCollections.observableArrayList();
    private final TextField installField = new TextField();
    private final Label status = label("", "card-note");
    private final VBox notesBox = new VBox(4);

    private PackageInventory inventory = PackageInventory.empty();
    private PackageRole role;
    private String section;
    private StackPane overlay;

    public PackagesView(AptService apt, RootSession root) {
        this.apt = apt;
        this.root = root;
        getStyleClass().add("scope-view");

        tree.setOnOpen((opened, chosen) -> {
            role = opened;
            section = chosen;
            refreshList();
        });
        tree.setOnPackage(chosen -> {
            list.getSelectionModel().select(chosen);
            list.scrollTo(chosen);
        });

        BorderPane body = new BorderPane(tree);
        body.setRight(buildPanel());
        BorderPane layout = new BorderPane(body);
        layout.setTop(buildMasthead());
        getChildren().add(layout);
    }

    /**
     * Builds the screen without a live service, for previews.
     */
    PackagesView() {
        this(null, null);
    }

    public void start() {
        refresh();
    }

    public void shutdown() {
        tree.stop();
        worker.shutdownNow();
    }

    // Reading

    private void refresh() {
        if (apt == null) {
            return;
        }
        tree.setLoading(true);
        worker.execute(() -> {
            PackageInventory read = apt.read();
            Platform.runLater(() -> {
                tree.setLoading(false);
                apply(read);
            });
        });
    }

    private void apply(PackageInventory read) {
        this.inventory = read;
        tree.setInventory(read);

        long waiting = read.upgradable();
        long security = read.security();
        headline.setText(security > 0 ? "SECURITY UPDATES" : waiting > 0 ? "UPDATES WAITING" : "UP TO DATE");
        headline.setStyle("-fx-text-fill: " + web(security > 0 ? ALERT : waiting > 0 ? AMBER : LIVE) + ";");
        long branches = read.packages().stream().map(PackageEntry::role).distinct().count();
        caption.setText((read.status().usable()
                ? "APT " + read.status().version() + "   ·   " + branches + " BRANCHES   ·   "
                        + read.manual() + " ASKED FOR, " + (read.total() - read.manual()) + " DEPENDENCIES"
                : "THIS SERVER DOES NOT USE APT").toUpperCase(Locale.ROOT));

        installedValue.setText(String.valueOf(read.total()));
        waitingValue.setText(String.valueOf(waiting));
        waitingValue.setStyle("-fx-text-fill: " + web(waiting > 0 ? AMBER : LIVE) + ";");
        securityValue.setText(String.valueOf(security));
        securityValue.setStyle("-fx-text-fill: " + web(security > 0 ? ALERT : LIVE) + ";");

        tagRow.getChildren().setAll(
                tag(bytes(read.bytes()) + " ON DISK"),
                tag(read.autoRemovable() + " NO LONGER NEEDED"),
                tag(read.status().rebootRequired() ? "RESTART REQUIRED" : "NO RESTART NEEDED"));

        notesBox.getChildren().clear();
        for (String note : read.notes()) {
            Label line = label("▸ " + note, "card-note");
            line.setWrapText(true);
            notesBox.getChildren().add(line);
        }
        notesBox.setVisible(!read.notes().isEmpty());
        notesBox.setManaged(!read.notes().isEmpty());
        refreshList();
    }

    /**
     * The right-hand list: the chosen family, or everything, narrowed by whatever is typed.
     */
    private void refreshList() {
        List<PackageEntry> source = new ArrayList<>();
        if (role == null) {
            source.addAll(inventory.waiting());
            listTitle.setText("WAITING FOR AN UPDATE");
            listNote.setText("OPEN A BRANCH ON THE TREE TO SEE WHAT IS IN IT");
            if (source.isEmpty()) {
                source.addAll(inventory.packages());
                listTitle.setText("EVERYTHING INSTALLED");
            }
        } else if (section == null) {
            inventory.packages().stream().filter(entry -> entry.role() == role).forEach(source::add);
            listTitle.setText(role.title());
            listNote.setText(role.note().toUpperCase(Locale.ROOT));
        } else {
            inventory.packages().stream()
                    .filter(entry -> entry.role() == role && entry.family().equals(section))
                    .forEach(source::add);
            listTitle.setText(role.title() + "  ·  " + section.toUpperCase(Locale.ROOT));
            listNote.setText("SECTION " + section.toUpperCase(Locale.ROOT) + " OF " + role.title());
        }
        if (askedForOnly.isSelected()) {
            source.removeIf(entry -> !entry.manual());
            listNote.setText(listNote.getText() + "  ·  DEPENDENCIES HIDDEN");
        }
        source.sort(java.util.Comparator.comparing(PackageEntry::manual).reversed()
                .thenComparing(java.util.Comparator.comparingLong(PackageEntry::installedKb).reversed())
                .thenComparing(PackageEntry::name));
        String filter = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
        List<PackageEntry> matching = filter.isEmpty() ? source
                : source.stream().filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(filter)).toList();
        shown.setAll(matching);
        listCount.setText(String.valueOf(matching.size()));
    }

    // Actions

    private void confirm(String title, String note, String expected, String action, boolean danger,
                         java.util.function.Supplier<CommandEntry> work, String done) {
        showOverlay(new PromptCard("PACKAGES", title, note, expected, action, danger, (typed, finish) -> {
            if (!typed.strip().equalsIgnoreCase(expected)) {
                finish.accept("Type " + expected + " exactly to confirm.");
                return;
            }
            runWithRoot(() -> worker.execute(() -> {
                CommandEntry result = work.get();
                Platform.runLater(() -> {
                    if (result.success()) {
                        hideOverlay();
                        report(done, false);
                        refresh();
                    } else {
                        finish.accept(firstLine(result.error().isBlank() ? result.output() : result.error()));
                    }
                });
            }));
        }, this::hideOverlay));
    }

    /**
     * Asks apt what a change would do, then shows that answer before letting it happen.
     */
    private void propose(String name, boolean installing) {
        if (apt == null) {
            return;
        }
        java.util.Optional<String> problem = AptCommands.nameProblem(name);
        if (problem.isPresent()) {
            report(problem.get(), true);
            return;
        }
        String clean = name.strip().toLowerCase(Locale.ROOT);
        report((installing ? "ASKING APT WHAT INSTALLING " : "ASKING APT WHAT REMOVING ") + clean + " WOULD DO…",
                false);
        worker.execute(() -> {
            String summary = installing ? apt.simulateInstall(clean) : apt.simulateRemove(clean);
            Platform.runLater(() -> {
                status.setText("");
                if (summary.toLowerCase(Locale.ROOT).startsWith("e:")) {
                    report(summary, true);
                    return;
                }
                confirm(installing ? "INSTALL " + clean : "REMOVE " + clean,
                        (summary.isBlank() ? "Apt did not say what it would do." : "Apt says: " + summary)
                                + "  Type the package name to confirm.",
                        clean, installing ? "INSTALL" : "REMOVE", !installing,
                        () -> installing ? apt.install(clean) : apt.remove(clean),
                        (installing ? "INSTALLED " : "REMOVED ") + clean.toUpperCase(Locale.ROOT));
            });
        });
    }

    private void run(String label, java.util.function.Supplier<CommandEntry> work) {
        runWithRoot(() -> {
            report(label + "…", false);
            worker.execute(() -> {
                CommandEntry result = work.get();
                Platform.runLater(() -> {
                    report(result.success() ? label + " DONE"
                            : firstLine(result.error().isBlank() ? result.output() : result.error()),
                            !result.success());
                    refresh();
                });
            });
        });
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
    }

    private static String firstLine(String text) {
        return text == null ? "FAILED" : text.strip().lines().findFirst().orElse("FAILED").toUpperCase(Locale.ROOT);
    }

    // Root

    private void runWithRoot(Runnable action) {
        if (apt != null && apt.needsPassword() && root != null) {
            showOverlay(new SudoPrompt(root.privilege(),
                    (command, done) -> worker.execute(() -> {
                        CommandEntry entry = apt.verify(command);
                        Platform.runLater(() -> done.accept(entry.success()));
                    }),
                    () -> {
                        root.refresh();
                        hideOverlay();
                        action.run();
                    },
                    this::hideOverlay));
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
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    // Building

    private VBox buildMasthead() {
        Label mark = label("P", "sheet-mark");
        Label kicker = label("PACKAGES.SCOPE", "series");
        HBox identity = new HBox(12, mark, kicker);
        identity.setAlignment(Pos.CENTER_LEFT);

        VBox naming = new VBox(2, headline, caption);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox readout = new HBox(0, readout("INSTALLED", installedValue), rule(),
                readout("WAITING", waitingValue), rule(), readout("SECURITY", securityValue));
        readout.getStyleClass().add("readout-bar");
        readout.setAlignment(Pos.CENTER_LEFT);
        HBox title = new HBox(26, naming, tagRow, spacer, readout);
        title.setAlignment(Pos.CENTER_LEFT);

        VBox masthead = new VBox(12, identity, title, buildRail());
        masthead.getStyleClass().add("sheet-masthead");
        masthead.setPadding(new Insets(14, 34, 0, 26));
        return masthead;
    }

    private HBox buildRail() {
        installField.getStyleClass().add("card-field");
        installField.setPromptText("package to install");
        installField.setPrefColumnCount(16);
        installField.setOnAction(event -> propose(installField.getText(), true));

        Button install = rail("+ INSTALL", event -> propose(installField.getText(), true));
        Button update = rail("↻ UPDATE INDEX", event -> run("UPDATING THE INDEX", apt::updateIndex));
        Button upgrade = rail("UPGRADE ALL", event -> confirm("UPGRADE EVERYTHING",
                "Apt will install every waiting version. Nothing is removed. Type UPGRADE to confirm.",
                "UPGRADE", "UPGRADE", false, apt::upgrade, "UPGRADE DONE"));
        Button full = rail("FULL UPGRADE", event -> confirm("FULL UPGRADE",
                "This may add and remove packages to satisfy new dependencies. Type FULL to confirm.",
                "FULL", "FULL UPGRADE", true, apt::fullUpgrade, "FULL UPGRADE DONE"));
        Button autoremove = rail("AUTOREMOVE", event -> confirm("REMOVE WHAT IS NO LONGER NEEDED",
                "Apt will remove the packages nothing depends on any more. Type REMOVE to confirm.",
                "REMOVE", "AUTOREMOVE", true, apt::autoremove, "AUTOREMOVE DONE"));
        Button clean = rail("CLEAN CACHE", event -> run("CLEANING THE CACHE", apt::clean));
        Button all = rail("SHOW EVERYTHING", event -> {
            tree.closeAll();
            role = null;
            section = null;
            refreshList();
        });
        Button zoomIn = rail("+", event -> tree.zoomIn());
        Button zoomOut = rail("−", event -> tree.zoomOut());
        Button fit = rail("FIT", event -> tree.fit());
        Button legend = rail("?", event -> showOverlay(new PackagesHelp(this::hideOverlay)));
        legend.setTooltip(new javafx.scene.control.Tooltip("What the colours, the rings and the arcs mean"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rail = new HBox(8, installField, install, separator(), update, upgrade, full,
                separator(), autoremove, clean, spacer, zoomOut, zoomIn, fit, separator(), all, legend);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setPadding(new Insets(0, 0, 10, 0));
        return rail;
    }

    private VBox buildPanel() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, listTitle, spacer, listCount);
        head.setAlignment(Pos.CENTER_LEFT);
        listNote.setWrapText(true);

        askedForOnly.getStyleClass().add("rail-button");
        askedForOnly.setFocusTraversable(false);
        askedForOnly.setMaxWidth(Double.MAX_VALUE);
        askedForOnly.setOnAction(event -> {
            tree.setAskedForOnly(askedForOnly.isSelected());
            refreshList();
        });

        filterField.getStyleClass().add("card-field");
        filterField.setPromptText("filter these packages");
        filterField.textProperty().addListener((observable, before, value) -> refreshList());

        list.setItems(shown);
        list.getStyleClass().add("redis-list");
        list.setFixedCellSize(34);
        list.setPlaceholder(label("NOTHING HERE.", "metric-label"));
        list.setCellFactory(view -> new PackageCell());
        list.getSelectionModel().selectedItemProperty()
                .addListener((observable, before, chosen) -> tree.reveal(chosen));
        VBox.setVgrow(list, Priority.ALWAYS);

        FlowPane actions = new FlowPane(8, 8,
                action("REMOVE SELECTED", "danger-button", event -> {
                    PackageEntry chosen = list.getSelectionModel().getSelectedItem();
                    if (chosen == null) {
                        report("PICK A PACKAGE FIRST.", true);
                    } else if (chosen.essential()) {
                        report("REFUSING: " + chosen.name().toUpperCase(Locale.ROOT)
                                + " IS ESSENTIAL AND REMOVING IT WOULD BREAK THE SERVER.", true);
                    } else {
                        propose(chosen.name(), false);
                    }
                }),
                action("WHAT IS IT?", "rail-button", event -> {
                    PackageEntry chosen = list.getSelectionModel().getSelectedItem();
                    if (chosen == null || apt == null) {
                        report("PICK A PACKAGE FIRST.", true);
                        return;
                    }
                    worker.execute(() -> {
                        CommandEntry entry = apt.describe(chosen.name());
                        String text = entry.output().isBlank() ? entry.error() : entry.output();
                        Platform.runLater(() -> showOverlay(PromptCard.output("PACKAGES",
                                chosen.name().toUpperCase(Locale.ROOT), text, this::hideOverlay)));
                    });
                }));
        actions.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        notesBox.setVisible(false);
        notesBox.setManaged(false);

        VBox column = new VBox(10, head, listNote, askedForOnly, filterField, list, actions, status,
                label("WHAT KUBO COULD NOT READ", "series"), notesBox);
        column.getStyleClass().add("redis-inspector");
        column.setPadding(new Insets(16, 26, 16, 20));
        column.setPrefWidth(392);
        column.setMinWidth(320);
        return column;
    }

    /**
     * One package: what it is called, what it weighs, and whether something is waiting for it.
     */
    private static final class PackageCell extends ListCell<PackageEntry> {

        private final Circle dot = new Circle(3.5);
        private final Label name = label("", "redis-row-name");
        private final Label note = label("", "redis-row-ttl");
        private final Label size = label("", "redis-row-ttl");
        private final VBox lines = new VBox(1, name, note);
        private final HBox row = new HBox(9, dot, lines, size);

        PackageCell() {
            getStyleClass().add("redis-row");
            row.setAlignment(Pos.CENTER_LEFT);
            lines.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(lines, Priority.ALWAYS);
        }

        @Override
        protected void updateItem(PackageEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Color colour = entry.security() ? ALERT : entry.upgradable() ? AMBER
                    : entry.autoRemovable() || !entry.manual() ? PackagePalette.QUIET : LIVE;
            dot.setFill(colour);
            name.setText(entry.name());
            note.setText(entry.upgradable()
                    ? entry.version() + "  →  " + entry.candidate() + (entry.security() ? "   SECURITY" : "")
                    : entry.autoRemovable() ? entry.version() + "   NO LONGER NEEDED"
                    : entry.version() + (entry.essential() ? "   ESSENTIAL" : ""));
            note.setStyle("-fx-text-fill: " + web(colour) + ";");
            size.setText(bytes(entry.installedBytes()));
            setGraphic(row);
        }
    }

    // Small shared pieces

    private VBox readout(String caption, Label value) {
        VBox box = new VBox(0, label(caption, "readout-caption"), value);
        box.setPadding(new Insets(0, 20, 0, 20));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("readout-rule");
        rule.setMinWidth(1);
        rule.setPrefWidth(1);
        rule.setMinHeight(30);
        return rule;
    }

    private static Region separator() {
        Region separator = new Region();
        separator.getStyleClass().add("rail-separator");
        separator.setMinWidth(1);
        separator.setPrefWidth(1);
        separator.setMinHeight(18);
        return separator;
    }

    private static Label tag(String text) {
        Label label = new Label("▸" + text);
        label.getStyleClass().add("sheet-tag");
        return label;
    }

    private static Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static Button action(String text, String styleClass,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-button");
        }
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(handler);
        return button;
    }

    private static String bytes(long value) {
        if (value >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.1f GB", value / 1_073_741_824.0);
        }
        if (value >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.0f MB", value / 1_048_576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // Previews reach in through these.

    PackageTree tree() {
        return tree;
    }

    void showForPreview(PackageInventory read) {
        apply(read);
    }

    void showLegendForPreview() {
        showOverlay(new PackagesHelp(this::hideOverlay));
    }
}
