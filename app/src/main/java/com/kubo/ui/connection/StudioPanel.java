package com.kubo.ui.connection;

import com.kubo.platform.Host;
import com.kubo.update.BuildInfo;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * What this program is and who made it, read out the way fastfetch reads out a machine: the mark on the
 * left, one fact per line on the right, every value on the same column, and the palette underneath.
 *
 * <p>It is also the only place in Kubo that points outside it. Every link here is handed to this computer's
 * browser; none of them is fetched by Kubo.
 */
final class StudioPanel extends VBox {

    private static final String EMAIL = "nellawassi@gmail.com";

    private final Label note = new Label();
    private final PauseTransition feedback = new PauseTransition(Duration.seconds(5));
    private final Button updates = link("check now", "Asks GitHub whether a newer Kubo has been released");
    private int row;

    void setOnCheckUpdates(Runnable check) {
        updates.setDisable(false);
        updates.setOnAction(event -> check.run());
    }

    StudioPanel() {
        getStyleClass().add("fetch");
        setSpacing(14);

        Label mark = new Label("K_");
        mark.getStyleClass().add("fetch-mark");
        mark.setMinWidth(Region.USE_PREF_SIZE);

        GridPane facts = new GridPane();
        facts.setHgap(16);
        facts.setVgap(4);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        facts.getColumnConstraints().addAll(keys, values);

        fact(facts, "kubo", text(version()));
        fact(facts, "studio", text("gemmie/ · kigali"));
        fact(facts, "site", open("kubo.gemmi-e.com", "https://kubo.gemmi-e.com"));
        fact(facts, "portfolio", open("gemmi-e.com/contact", "https://gemmi-e.com/contact"));
        fact(facts, "source", open("github.com/Lightminedust/kubo-app", "https://github.com/Lightminedust/kubo-app"));
        fact(facts, "issues", new HBox(14,
                open("report a bug", "https://github.com/Lightminedust/kubo-app/issues/new"),
                open("ask for something", "https://github.com/Lightminedust/kubo-app/issues/new")));
        fact(facts, "mail", mail());
        updates.setDisable(true);
        fact(facts, "updates", updates);
        HBox.setHgrow(facts, Priority.ALWAYS);

        HBox block = new HBox(22, mark, facts);
        block.setAlignment(Pos.TOP_LEFT);

        // The eight blocks every fetch ends on. Here they are Kubo's own colours, so it doubles as the legend.
        HBox palette = new HBox(0);
        for (String colour : new String[]{"#ff2d55", "#9d4dff", "#3b3bff", "#35e8ff", "#35e0a1", "#e8c35a",
                "#a8a8b6", "#2a2a36"}) {
            Region swatch = new Region();
            swatch.setMinSize(22, 10);
            swatch.setStyle("-fx-background-color: " + colour + ";");
            palette.getChildren().add(swatch);
        }

        note.getStyleClass().add("fetch-note");
        note.setWrapText(true);
        note.setVisible(false);
        note.setManaged(false);
        feedback.setOnFinished(event -> {
            note.setVisible(false);
            note.setManaged(false);
        });
        getChildren().addAll(block, palette, note);
    }

    private static String version() {
        try {
            BuildInfo build = BuildInfo.current();
            return build.version() + " · " + build.kind().label().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException unknown) {
            return "development build";
        }
    }

    private void fact(GridPane grid, String key, javafx.scene.Node value) {
        Label label = new Label(key);
        label.getStyleClass().add("fetch-key");
        grid.add(label, 0, row);
        grid.add(value, 1, row++);
    }

    private static Label text(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("fetch-value");
        label.setMinWidth(0);
        return label;
    }

    private Button open(String text, String url) {
        Button button = link(text, url);
        button.setOnAction(event -> {
            try {
                Host.openLink(url);
            } catch (java.io.IOException | RuntimeException unavailable) {
                copy(url, "no browser answered, the link is on the clipboard");
            }
        });
        return button;
    }

    private Button mail() {
        Button button = link(EMAIL, "mailto:" + EMAIL);
        button.setOnAction(event -> {
            try {
                java.awt.Desktop.getDesktop().mail(java.net.URI.create("mailto:" + EMAIL));
            } catch (java.io.IOException | RuntimeException unavailable) {
                copy(EMAIL, "no mail program answered, the address is on the clipboard");
            }
        });
        return button;
    }

    private static Button link(String text, String destination) {
        Button button = new Button(text);
        button.getStyleClass().add("fetch-link");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(destination));
        button.setAccessibleText(text + ", " + destination);
        return button;
    }

    private void copy(String value, String message) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        boolean copied = Clipboard.getSystemClipboard().setContent(content);
        note.setText(copied ? message : value);
        note.setVisible(true);
        note.setManaged(true);
        feedback.playFromStart();
    }
}
