package com.kubo.ui;

import com.kubo.model.tools.PackageManager;
import com.kubo.model.tools.Tool;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The tools Kubo can use if the server has them, and the offer to install one.
 *
 * <p>This is the only place in Kubo that adds anything to a server, so every row says the same three things in
 * the same three places: the name, what it unlocks, and whether it is there. The command that would run is not
 * printed under every row any more, cut off at the width of the card; it is behind the row, one click away, in
 * full, because nobody should have to trust a button that writes to /usr without reading it first.
 *
 * <p>An install replaces its own row with a readout: the phase, the bytes, the packages and the server's own
 * lines as they come. Nothing else on the card moves while it runs.
 */
public final class ToolsCard extends VBox {

    /** What the card asks the screen to do. */
    public interface Hands {
        /** Installs one tool and reports with null when it worked, or the reason it did not. */
        void install(Tool tool, java.util.function.Consumer<String> done);

        /**
         * Installs one tool, handing every line the package manager prints to {@code onLine} as it arrives.
         *
         * <p>The default sends nothing and falls back to {@link #install}, so a screen that has no way to watch
         * still works: it shows the phase and the clock, and the lines arrive at the end.
         */
        default void install(Tool tool, java.util.function.Consumer<String> onLine,
                             java.util.function.Consumer<String> done) {
            install(tool, done);
        }
    }

    private final VBox body = new VBox(22);
    private final Pulse pulse = new Pulse();
    private final Label status = new Label();
    private final Hands hands;
    private final boolean canInstall;
    private final String cannotInstallBecause;
    /** The rows whose command the reader asked to see. */
    private final Set<Tool> showingCommand = java.util.EnumSet.noneOf(Tool.class);
    /**
     * The readouts of installs run on this card, kept so the row can be rebuilt without losing them.
     *
     * <p>The row has to be rebuilt: an install that worked turns INSTALL into INSTALLED and the row's edge
     * green, and saying "it is installed" in a sentence under a row still offering to install it was the
     * change the operator was looking for and did not get.
     */
    private final Map<Tool, InstallReadout> readouts = new EnumMap<>(Tool.class);
    private Set<Tool> present = Set.of();
    private PackageManager manager = PackageManager.NONE;
    private boolean filled;
    /** The tool being installed right now, while one is: no second install starts underneath it. */
    private Tool installing;

    /**
     * @param canInstall           false when root is off, which is what installing needs whoever is connected
     * @param cannotInstallBecause shown in place of the buttons when it cannot
     */
    public ToolsCard(Hands hands, boolean canInstall, String cannotInstallBecause, Runnable onClose) {
        this.hands = hands;
        this.canInstall = canInstall;
        this.cannotInstallBecause = cannotInstallBecause;
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(16);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("TOOLS KUBO CAN USE", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(660);
        status.setVisible(false);
        status.setManaged(false);

        body.getChildren().add(note("Looking at what this server already has."));
        pulse.setRunning(true);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(500);
        scroll.setPrefViewportWidth(700);
        scroll.setMaxWidth(710);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, pulse, scroll, status);
    }

    /**
     * Fills the card once the server has answered.
     */
    public void show(Set<Tool> present, PackageManager manager) {
        this.present = Set.copyOf(present);
        this.manager = manager;
        this.filled = true;
        pulse.setRunning(false);
        draw();
    }

    /** Only for the previews: opens one row's command, the way clicking COMMAND does. */
    public void showCommandForPreview(Tool tool) {
        showingCommand.add(tool);
        draw();
    }

    /**
     * Only for the previews: puts an install readout in one row, filled with lines a server printed, without
     * a server.
     */
    public void showInstallingForPreview(Tool tool, java.util.List<String> printed) {
        showInstallingForPreview(tool, printed, false);
    }

    /**
     * @param finished true to show it as it looks once the install worked, with the row turned over
     */
    public void showInstallingForPreview(Tool tool, java.util.List<String> printed, boolean finished) {
        InstallReadout readout = new InstallReadout(tool.binary(), manager.binary());
        readouts.put(tool, readout);
        if (finished) {
            present = java.util.stream.Stream.concat(present.stream(), java.util.stream.Stream.of(tool))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            report(null, tool.binary() + " is installed. The features it feeds work from the next reading.");
        } else {
            installing = tool;
        }
        draw();
        readout.showForPreview(printed, finished, finished);
    }

    /** Says the read failed, rather than showing an empty list that reads as "this server has nothing". */
    public void showProblem(String reason) {
        pulse.setRunning(false);
        filled = false;
        body.getChildren().setAll(problem("Kubo could not read which tools this server has: " + reason));
    }

    private void draw() {
        if (!filled) {
            return;
        }
        body.getChildren().clear();

        body.getChildren().add(note("Kubo needs none of these to run. Each one turns a feature that is greyed "
                + "out into one that works, and nothing else on your server changes."));
        body.getChildren().add(summary());
        if (!manager.known()) {
            body.getChildren().add(problem("No package manager Kubo knows how to drive was found on this "
                    + "server, so it can only tell you what is missing. Install by hand what you want."));
        } else if (!canInstall) {
            body.getChildren().add(problem(cannotInstallBecause));
        }

        for (Tool.Group group : Tool.Group.values()) {
            VBox rows = new VBox(1);
            for (Tool tool : Tool.of(group)) {
                rows.getChildren().add(row(tool));
            }
            VBox section = new VBox(8, label(group.title(), "series"), note(group.why()), rows);
            body.getChildren().add(section);
        }
    }

    /**
     * One line saying where this server stands, so the list below it is read as an answer rather than a menu.
     */
    private HBox summary() {
        int here = (int) java.util.Arrays.stream(Tool.values()).filter(present::contains).count();
        Label count = label(here + " OF " + Tool.values().length + " ALREADY HERE", "mono");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label driver = label(manager.known() ? manager.binary() + "  ·  " + manager.family() : "NO PACKAGE MANAGER",
                "mono-muted");
        HBox line = new HBox(12, count, spacer, driver);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("tools-summary");
        line.setPadding(new Insets(8, 12, 9, 12));
        return line;
    }

    /**
     * One tool: name on the left, state on the right, the sentence underneath, and the command behind a click.
     */
    private VBox row(Tool tool) {
        boolean here = present.contains(tool);

        Label name = label(tool.binary(), "mono");
        name.setMinWidth(112);
        name.setPrefWidth(112);

        Label what = label(tool.unlocks(), "card-note");
        what.setWrapText(true);
        what.setMaxWidth(320);
        what.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(what, Priority.ALWAYS);

        HBox head = new HBox(14, name, what, state(tool, here));
        head.setAlignment(Pos.TOP_LEFT);

        VBox block = new VBox(6, head);
        block.getStyleClass().add(here ? "tool-row-here" : "tool-row");
        block.setPadding(new Insets(10, 12, 11, 12));

        if (!tool.caveat().isBlank()) {
            Label caveat = label(tool.caveat(), "tool-caveat");
            caveat.setWrapText(true);
            caveat.setMaxWidth(430);
            caveat.setMinHeight(Region.USE_PREF_SIZE);
            VBox.setMargin(caveat, new Insets(0, 0, 0, 126));
            block.getChildren().add(caveat);
        }
        InstallReadout readout = readouts.get(tool);
        if (readout != null) {
            block.getChildren().add(readout);
        }
        if (showingCommand.contains(tool) && manager.known()) {
            TextArea command = new TextArea(manager.install(tool.packageFor(manager)));
            command.setEditable(false);
            command.setWrapText(true);
            // Three lines with the box's own padding on top of them. Counted in pixels rather than in rows
            // because a row count leaves the third line clipped, which is the thing this was meant to stop.
            command.setPrefHeight(68);
            command.setMinHeight(68);
            command.getStyleClass().add("redis-diagnostic-output");
            VBox.setMargin(command, new Insets(2, 0, 0, 126));
            block.getChildren().add(command);
        }
        return block;
    }

    /**
     * The right hand column: one thing, always in the same place.
     */
    private HBox state(Tool tool, boolean here) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_RIGHT);
        // Wide enough for both buttons at their full width: a label cut to "IN..." is worse than no label.
        box.setMinWidth(196);
        box.setPrefWidth(196);

        if (here) {
            box.getChildren().add(label("INSTALLED", "card-success"));
            return box;
        }
        if (!manager.known()) {
            box.getChildren().add(label("MISSING", "card-problem"));
            return box;
        }

        Button show = new Button(showingCommand.contains(tool) ? "HIDE" : "COMMAND");
        show.getStyleClass().addAll("ghost-button", "tight-button");
        show.setFocusTraversable(false);
        show.setOnAction(event -> {
            if (!showingCommand.remove(tool)) {
                showingCommand.add(tool);
            }
            draw();
        });

        Button install = new Button("INSTALL");
        install.getStyleClass().addAll("rail-button", "tight-button");
        install.setFocusTraversable(false);
        install.setDisable(!canInstall || installing != null);
        install.setOnAction(event -> run(tool));

        box.getChildren().addAll(show, install);
        return box;
    }

    /**
     * Starts an install and turns its row into a readout for as long as it runs.
     */
    private void run(Tool tool) {
        InstallReadout readout = new InstallReadout(tool.binary(), manager.binary());
        readouts.put(tool, readout);
        installing = tool;
        status.setVisible(false);
        status.setManaged(false);
        draw();
        readout.start();

        hands.install(tool,
                line -> javafx.application.Platform.runLater(() -> readout.accept(line)),
                trouble -> {
                    readout.finish(trouble == null);
                    installing = null;
                    if (trouble == null) {
                        present = java.util.stream.Stream.concat(present.stream(), java.util.stream.Stream.of(tool))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    }
                    report(trouble, tool.binary() + " is installed. The features it feeds work from the next "
                            + "reading.");
                    // Redrawn now, which is what turns the row green and its button into INSTALLED. The readout
                    // is kept and put back, so the lines the server printed do not go with the old row.
                    draw();
                });
    }

    private void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    private static Label problem(String text) {
        Label label = label(text, "card-problem");
        label.setWrapText(true);
        label.setMaxWidth(660);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(660);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
