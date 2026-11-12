package com.kubo.ui.services;

import com.kubo.model.services.Resource;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Holding a service back without stopping it: a memory ceiling and a share of the processor.
 *
 * <p>A choice fills the field and sends nothing; "set" sends what the field says. A value systemd would not
 * take is refused here, before it goes near a command.
 */
final class LimitCard extends VBox {

    /** Sends one limit. The callback is called with null once it is set, or with what went wrong. */
    interface Setter {
        void set(Resource resource, String value, Consumer<String> done);
    }

    private static final String[][] MEMORY = {{"256M", "256 MB"}, {"512M", "512 MB"}, {"1G", "1 GB"},
        {"2G", "2 GB"}, {"infinity", "no limit"}};
    private static final String[][] CPU = {{"25%", "a quarter"}, {"50%", "half a core"}, {"100%", "one core"},
        {"200%", "two cores"}, {"infinity", "no limit"}};

    private final Label result = new Label();

    LimitCard(String unit, String memoryNow, String memoryLimit, String cpuLimit, Setter setter,
              Runnable onClose) {
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(16);
        setPadding(new Insets(22, 28, 20, 26));
        setPrefWidth(580);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label("limits of " + unit);
        title.getStyleClass().add("fetch-host");
        Label under = new Label("held back now, no restart");
        under.getStyleClass().add("home-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = ServicesScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, under, spacer, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        GridPane form = new GridPane();
        form.setHgap(18);
        form.setVgap(10);
        form.addRow(0, key("memory"), row(Resource.MEMORY_MAX, MEMORY, setter,
                "uses " + memoryNow + "  ·  limit " + memoryLimit + ". past it, the service is killed"));
        form.addRow(1, key("cpu"), row(Resource.CPU_QUOTA, CPU, setter,
                "limit " + cpuLimit + ". 100% is one whole core"));

        result.setWrapText(true);
        result.setMinHeight(Region.USE_PREF_SIZE);
        result.setVisible(false);
        result.setManaged(false);
        Label note = new Label("systemd keeps them across restarts. no limit takes one off again.");
        note.getStyleClass().add("home-muted");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);

        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        getChildren().addAll(top, form, note, result);
    }

    private VBox row(Resource resource, String[][] choices, Setter setter, String hint) {
        TextField field = new TextField();
        field.getStyleClass().add("card-field");
        field.setPromptText(resource == Resource.MEMORY_MAX ? "512M, 2G or infinity" : "50%, 200% or infinity");
        field.setPrefColumnCount(12);
        Button set = ServicesScope.action("set", null);
        set.getStyleClass().add("apps-lit");
        Runnable send = () -> {
            String value = field.getText().strip();
            if (!resource.accepts(value)) {
                say(false, (value.isEmpty() ? "nothing typed" : "“" + value + "” is not a value systemd takes")
                        + ". " + resource.meaning());
                return;
            }
            set.setDisable(true);
            set.setText("setting…");
            setter.set(resource, value, trouble -> {
                set.setDisable(false);
                set.setText("set");
                say(trouble == null, trouble == null
                        ? (resource == Resource.MEMORY_MAX ? "memory" : "cpu") + " limit set to " + value
                        : trouble);
            });
        };
        set.setOnAction(event -> send.run());
        field.setOnAction(event -> send.run());
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox line = new HBox(14, field, set);
        line.setAlignment(Pos.CENTER_LEFT);

        FlowPane chips = new FlowPane(8, 6);
        for (String[] choice : choices) {
            Button chip = new Button(choice[1]);
            chip.getStyleClass().add("service-chip");
            chip.setFocusTraversable(false);
            chip.setMinWidth(Region.USE_PREF_SIZE);
            chip.setOnAction(event -> {
                field.setText(choice[0]);
                field.positionCaret(choice[0].length());
            });
            chips.getChildren().add(chip);
        }
        Label under = new Label(hint);
        under.getStyleClass().add("home-muted");
        under.setWrapText(true);
        under.setMinHeight(Region.USE_PREF_SIZE);
        return new VBox(7, line, chips, under);
    }

    private void say(boolean good, String text) {
        result.getStyleClass().setAll("label", good ? "card-success" : "card-problem");
        result.setText(text);
        result.setVisible(true);
        result.setManaged(true);
    }

    private static Label key(String text) {
        Label key = new Label(text);
        key.getStyleClass().add("fetch-key");
        key.setMinWidth(62);
        key.setPadding(new Insets(5, 0, 0, 0));
        GridPane.setValignment(key, javafx.geometry.VPos.TOP);
        return key;
    }
}
