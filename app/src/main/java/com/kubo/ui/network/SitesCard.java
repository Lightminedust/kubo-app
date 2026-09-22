package com.kubo.ui.network;

import com.kubo.model.network.NginxSites;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The Nginx sites, on and off.
 *
 * <p>{@code nginx -T} only prints what is switched on, so a site that exists and is disabled never appeared
 * anywhere on this screen. That is exactly the file somebody goes looking for when a site stopped answering.
 */
final class SitesCard extends VBox {

    interface Hands {
        void enable(String site);

        void disable(String site);

        /** Runs nginx -t and reports, changing nothing. */
        void test();
    }

    private final Label status = new Label();

    SitesCard(NginxSites sites, Hands hands, Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button test = new Button("TEST THE CONFIGURATION");
        test.getStyleClass().addAll("rail-button", "compact-button");
        test.setFocusTraversable(false);
        test.setOnAction(event -> hands.test());
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("NGINX SITES", "card-title"), spacer, test, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(660);
        status.setVisible(false);
        status.setManaged(false);

        VBox body = new VBox(14);
        if (sites.empty()) {
            body.getChildren().add(note("No site file was found. Either this server does not use the "
                    + "sites-available layout, or reading those directories needs root."));
        } else {
            if (!sites.dangling().isEmpty()) {
                body.getChildren().addAll(label("BROKEN LINKS", "series"),
                        note("These are switched on but their file is gone from sites-available. Nginx refuses "
                                + "to start at all while one of them is there."));
                for (String site : sites.dangling()) {
                    body.getChildren().add(row(site, true, hands, true));
                }
            }
            body.getChildren().add(label("ON", "series"));
            boolean any = false;
            for (String site : sites.enabled()) {
                if (sites.available().contains(site)) {
                    body.getChildren().add(row(site, true, hands, false));
                    any = true;
                }
            }
            if (!any) {
                body.getChildren().add(note("No site is switched on."));
            }

            body.getChildren().add(label("OFF", "series"));
            if (sites.disabled().isEmpty()) {
                body.getChildren().add(note("Every site that exists is switched on."));
            } else {
                for (String site : sites.disabled()) {
                    body.getChildren().add(row(site, false, hands, false));
                }
            }
        }
        body.getChildren().add(status);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(460);
        scroll.setMaxWidth(700);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    private HBox row(String site, boolean on, Hands hands, boolean broken) {
        Label name = label(site, "mono");
        name.setMinWidth(260);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button toggle = new Button(on ? "SWITCH OFF" : "SWITCH ON");
        toggle.getStyleClass().addAll(on ? "danger-button" : "rail-button", "compact-button");
        toggle.setFocusTraversable(false);
        toggle.setOnAction(event -> {
            if (on) {
                hands.disable(site);
            } else {
                hands.enable(site);
            }
        });

        HBox row = new HBox(12, name, spacer,
                label(broken ? "file missing" : "", "card-problem"), toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(640);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
