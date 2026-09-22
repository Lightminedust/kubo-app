package com.kubo.ui;

import com.kubo.model.tools.PackageManager;
import com.kubo.model.tools.Tool;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
 * <p>This is the only place in Kubo that adds anything to a server. So it says, for each one, what it unlocks
 * and what it still will not do, shows the exact command before running it, and installs one tool at a time on
 * one click. Nothing here happens on its own, and nothing is installed to make Kubo work: without any of them
 * every screen does what it did before, with the feature these feed greyed out and the reason beside it.
 */
public final class ToolsCard extends VBox {

    /** What the card asks the screen to do. */
    public interface Hands {
        /** Installs one tool and reports with null when it worked, or the reason it did not. */
        void install(Tool tool, java.util.function.Consumer<String> done);
    }

    private final VBox body = new VBox(18);
    private final Pulse pulse = new Pulse();
    private final Label status = new Label();
    private final Map<Tool, Button> buttons = new EnumMap<>(Tool.class);
    private final Hands hands;
    private final boolean canInstall;
    private final String cannotInstallBecause;

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
        setSpacing(14);
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

        body.getChildren().add(label("LOOKING AT WHAT THIS SERVER ALREADY HAS", "card-note"));
        pulse.setRunning(true);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(740);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, pulse, scroll);
    }

    /**
     * Fills the card once the server has answered.
     */
    public void show(Set<Tool> present, PackageManager manager) {
        pulse.setRunning(false);
        body.getChildren().clear();
        buttons.clear();

        body.getChildren().add(note("Kubo needs none of these to run. Each one turns a feature that is greyed "
                + "out into one that works, and nothing else on your server changes."));

        if (!manager.known()) {
            body.getChildren().add(problem("No package manager Kubo knows how to drive was found on this "
                    + "server, so it can only tell you what is missing. Install by hand what you want."));
        } else {
            body.getChildren().add(note("This server uses " + manager.binary() + "  ·  " + manager.family()));
        }
        if (!canInstall && manager.known()) {
            body.getChildren().add(problem(cannotInstallBecause));
        }

        for (Tool.Group group : Tool.Group.values()) {
            body.getChildren().add(label(group.title(), "series"));
            body.getChildren().add(note(group.why()));
            for (Tool tool : Tool.of(group)) {
                body.getChildren().add(row(tool, present.contains(tool), manager));
            }
        }
        body.getChildren().add(status);
    }

    /** Says the read failed, rather than showing an empty list that reads as "this server has nothing". */
    public void showProblem(String reason) {
        pulse.setRunning(false);
        body.getChildren().setAll(problem("Kubo could not read which tools this server has: " + reason));
    }

    private VBox row(Tool tool, boolean here, PackageManager manager) {
        Label name = label(tool.binary(), "mono");
        name.setMinWidth(120);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox head = new HBox(12, name, spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        if (here) {
            head.getChildren().add(label("INSTALLED", "card-success"));
        } else if (manager.known()) {
            Button install = new Button("INSTALL");
            install.getStyleClass().addAll("rail-button", "compact-button");
            install.setFocusTraversable(false);
            install.setDisable(!canInstall);
            install.setTooltip(new javafx.scene.control.Tooltip(
                    manager.install(tool.packageFor(manager))));
            install.setOnAction(event -> run(tool, install));
            buttons.put(tool, install);
            head.getChildren().add(install);
        } else {
            head.getChildren().add(label("MISSING", "card-problem"));
        }

        VBox block = new VBox(2, head, note(tool.unlocks()));
        if (!tool.caveat().isBlank()) {
            block.getChildren().add(label(tool.caveat(), "mono-muted"));
        }
        if (!here && manager.known()) {
            // The exact sentence, before it runs. Nobody should have to trust a button that writes to /usr.
            block.getChildren().add(label(manager.install(tool.packageFor(manager)), "mono-muted"));
        }
        return block;
    }

    private void run(Tool tool, Button install) {
        install.setDisable(true);
        install.setText("INSTALLING…");
        pulse.setRunning(true);
        hands.install(tool, trouble -> {
            pulse.setRunning(false);
            install.setText(trouble == null ? "INSTALLED" : "INSTALL");
            install.setDisable(trouble == null);
            report(trouble, tool.binary() + " is installed. The features it feeds work from the next reading.");
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
