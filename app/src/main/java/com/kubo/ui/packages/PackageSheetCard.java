package com.kubo.ui.packages;

import com.kubo.model.packages.PackageSheet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * What a package is, on a card rather than as the control paragraph apt prints.
 *
 * <p>The sentence saying what the thing is for goes at the top, where somebody who clicked WHAT IS IT? is
 * looking, instead of under ten lines of checksums. The facts that fit on one line become badges. What it
 * depends on becomes buttons, because the question after "what is this" is almost always "and what is that",
 * and going back to the search box to ask breaks the thread.
 *
 * <p>The back button is the point of keeping a trail: three dependencies deep is easy to reach and impossible
 * to get out of otherwise.
 */
final class PackageSheetCard extends VBox {

    /** How the card reaches the server. */
    interface Hands {

        /** Reads another package's sheet, for a dependency that was clicked. */
        void open(String name, Consumer<PackageSheet> done);

        /** Opens a link in the operator's own browser, on their own machine. */
        void browse(String url);
    }

    private final Hands hands;
    private final Deque<PackageSheet> trail = new ArrayDeque<>();
    private final VBox body = new VBox(14);
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Button back = new Button("← BACK");

    PackageSheetCard(PackageSheet sheet, Hands hands, Runnable onClose) {
        this.hands = hands;
        getStyleClass().addAll("float-card", "wide-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        title.getStyleClass().add("card-title");
        subtitle.getStyleClass().add("mono-muted");
        back.getStyleClass().addAll("ghost-button", "compact-button");
        back.setFocusTraversable(false);
        back.setVisible(false);
        back.setManaged(false);
        back.setOnAction(event -> {
            trail.pop();
            draw(trail.peek());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, new VBox(2, title, subtitle), spacer, back, close);
        header.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(420);
        scroll.setMaxWidth(860);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);

        trail.push(sheet);
        draw(sheet);
    }

    /** Follows a dependency, keeping the way back. */
    private void follow(String name) {
        subtitle.setText("READING " + name.toUpperCase(Locale.ROOT) + "…");
        hands.open(name, read -> {
            if (read == null || read.isEmpty()) {
                subtitle.setText("APT KNOWS NOTHING ABOUT " + name.toUpperCase(Locale.ROOT));
                return;
            }
            trail.push(read);
            draw(read);
        });
    }

    private void draw(PackageSheet sheet) {
        if (sheet == null) {
            return;
        }
        title.setText(sheet.name().toUpperCase(Locale.ROOT));
        subtitle.setText(sheet.version());
        back.setVisible(trail.size() > 1);
        back.setManaged(trail.size() > 1);
        body.getChildren().clear();

        if (!sheet.summary().isBlank()) {
            body.getChildren().add(lede(sheet.summary()));
        }
        FlowPane badges = new FlowPane(8, 8);
        badge(badges, "SECTION", sheet.section());
        badge(badges, "ARCHITECTURE", sheet.architecture());
        badge(badges, "ON DISK", sheet.installedKb() > 0
                ? PackageSheet.bytes(sheet.installedKb() * 1024) : "");
        badge(badges, "DOWNLOAD", PackageSheet.bytes(sheet.downloadBytes()));
        if (!badges.getChildren().isEmpty()) {
            body.getChildren().add(badges);
        }

        if (!sheet.description().isBlank()) {
            body.getChildren().addAll(label("WHAT IT SAYS ABOUT ITSELF", "series"), note(sheet.description()));
        }
        if (!sheet.maintainer().isBlank()) {
            body.getChildren().addAll(label("MAINTAINED BY", "series"), note(sheet.maintainer()));
        }
        if (!sheet.homepage().isBlank()) {
            Button link = new Button(sheet.homepage());
            link.getStyleClass().addAll("rail-button", "compact-button");
            link.setFocusTraversable(false);
            // Opens on this computer, never on the server: the server has no browser and no screen.
            link.setOnAction(event -> hands.browse(sheet.homepage()));
            body.getChildren().addAll(label("ITS PAGE", "series"), link,
                    note("Opens on this computer, not on the server."));
        }

        dependencies(sheet.depends(), "WHAT IT NEEDS",
                "Every one of these has to be installed for it to run. Click one to read it.");
        dependencies(sheet.recommends(), "WHAT IT SUGGESTS",
                "Installed alongside by default, and it runs without them.");

        if (!sheet.other().isEmpty()) {
            VBox rest = new VBox(2);
            sheet.other().forEach((field, value) -> {
                if (value.isBlank() || value.contains("\n")) {
                    return;
                }
                Label name = label(field.toUpperCase(Locale.ROOT), "metric-label");
                name.setMinWidth(180);
                HBox row = new HBox(10, name, label(value, "mono-muted"));
                row.setAlignment(Pos.CENTER_LEFT);
                rest.getChildren().add(row);
            });
            if (!rest.getChildren().isEmpty()) {
                body.getChildren().addAll(label("EVERYTHING ELSE APT SAYS", "series"), rest);
            }
        }
    }

    private void dependencies(List<String> names, String heading, String what) {
        if (names.isEmpty()) {
            return;
        }
        FlowPane pane = new FlowPane(6, 6);
        for (String name : names) {
            Button chip = new Button(name);
            chip.getStyleClass().addAll("rail-button", "compact-button");
            chip.setFocusTraversable(false);
            chip.setOnAction(event -> follow(name));
            pane.getChildren().add(chip);
        }
        body.getChildren().addAll(label(heading + "  ·  " + names.size(), "series"), note(what), pane);
    }

    private static void badge(FlowPane into, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Label chip = label(name + "  " + value, "net-chip");
        into.getChildren().add(chip);
    }

    /** Only for the previews: the card as it looks after following one dependency. */
    void followForPreview(String name) {
        follow(name);
    }

    private static Label lede(String text) {
        Label label = label(text, "lede");
        label.setWrapText(true);
        label.setMaxWidth(760);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(760);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
