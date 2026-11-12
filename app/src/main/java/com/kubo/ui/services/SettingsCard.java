package com.kubo.ui.services;

import com.kubo.model.services.DropIn;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The service's own settings, in a file beside the one its package shipped.
 *
 * <p>The package's file is never touched. systemd reads this one after it, so what is written here wins, and
 * removing it puts the service back as the package left it. The choice of what happens when it dies writes
 * its line into the text, so what is about to be saved is on the card before it is saved.
 */
final class SettingsCard extends VBox {

    /** What the card asks the screen to do. Each callback is called with null when it worked. */
    interface Hands {
        void save(String content, Consumer<String> done);

        void remove(Consumer<String> done);
    }

    private static final String[][] POLICIES = {{"no", "never restart"}, {"on-failure", "on failure"},
        {"always", "always"}};

    private final TextArea text = new TextArea();
    private final List<Button> policies = new ArrayList<>();
    private final Label result = new Label();

    /**
     * @param current what the file holds now, or empty when it has none
     * @param restart the policy systemd uses now, from the unit and every file beside it
     */
    SettingsCard(String unit, String path, String current, String restart, Hands hands, Runnable onClose) {
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(14);
        setPadding(new Insets(22, 28, 20, 26));
        setPrefWidth(620);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        boolean exists = current != null && !current.isBlank();
        Label title = new Label("settings of " + unit);
        title.getStyleClass().add("fetch-host");
        Label under = new Label(exists ? "its own file, read after the package's" : "none yet");
        under.getStyleClass().add("home-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = ServicesScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, under, spacer, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        HBox dies = new HBox(8, key("when it dies"));
        dies.setAlignment(Pos.CENTER_LEFT);
        for (String[] policy : POLICIES) {
            Button chip = new Button(policy[1]);
            chip.getStyleClass().add("service-chip");
            chip.setFocusTraversable(false);
            chip.setMinWidth(Region.USE_PREF_SIZE);
            chip.setUserData(policy[0]);
            chip.setOnAction(event -> {
                text.setText(DropIn.with(text.getText(), "Restart", policy[0]));
                light();
            });
            policies.add(chip);
            dies.getChildren().add(chip);
        }
        Label now = new Label(restart == null || restart.isBlank() ? "" : "now: " + restart);
        now.getStyleClass().add("home-muted");
        dies.getChildren().add(now);

        text.getStyleClass().add("redis-diagnostic-output");
        text.setWrapText(false);
        text.setPrefRowCount(8);
        text.setText(exists ? current.strip() + "\n" : DropIn.SECTION + "\n");
        text.textProperty().addListener((observable, before, after) -> light());

        Label where = new Label((exists ? "" : "saved to ") + path);
        where.getStyleClass().add(exists ? "fetch-value" : "port-quiet");
        where.setWrapText(true);
        where.setMinHeight(Region.USE_PREF_SIZE);
        Label note = new Label("a setting that only counts at start still needs a restart.");
        note.getStyleClass().add("home-muted");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);

        result.setWrapText(true);
        result.setMinHeight(Region.USE_PREF_SIZE);
        result.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(result, Priority.ALWAYS);
        Button remove = ServicesScope.action("remove them", null);
        remove.getStyleClass().add("port-danger");
        remove.setVisible(exists);
        remove.setManaged(exists);
        Button save = ServicesScope.action("save and reload", null);
        save.getStyleClass().add("apps-lit");
        save.setOnAction(event -> {
            if (DropIn.saysNothing(text.getText())) {
                say(false, exists ? "nothing to save. remove them takes the file away" : "nothing to save yet");
                return;
            }
            save.setDisable(true);
            save.setText("saving…");
            hands.save(text.getText(), trouble -> {
                save.setDisable(false);
                save.setText("save and reload");
                say(trouble == null, trouble == null ? "saved, and systemd reloaded" : trouble);
            });
        });
        remove.setOnAction(event -> {
            remove.setDisable(true);
            hands.remove(trouble -> {
                remove.setDisable(false);
                if (trouble == null) {
                    text.setText(DropIn.SECTION + "\n");
                }
                say(trouble == null, trouble == null ? "removed: it is back as its package left it" : trouble);
            });
        });
        HBox buttons = new HBox(16, result, remove, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        getChildren().addAll(top, dies, text, new VBox(4, where, note), buttons);
        light();
    }

    /** Lights the policy the text says, when it says one. */
    private void light() {
        String value = DropIn.valueOf(text.getText(), "Restart");
        for (Button chip : policies) {
            chip.getStyleClass().remove("service-chip-active");
            if (chip.getUserData().equals(value)) {
                chip.getStyleClass().add("service-chip-active");
            }
        }
    }

    private void say(boolean good, String words) {
        result.getStyleClass().setAll("label", good ? "card-success" : "card-problem");
        result.setText(words);
    }

    private static Label key(String text) {
        Label key = new Label(text);
        key.getStyleClass().add("fetch-key");
        key.setMinWidth(Region.USE_PREF_SIZE);
        key.setPadding(new Insets(0, 8, 0, 0));
        return key;
    }
}
