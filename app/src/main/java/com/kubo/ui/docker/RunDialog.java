package com.kubo.ui.docker;

import com.kubo.docker.DockerCommands;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * A small form to start a new container: the image, an optional name and one optional published port. Kubo runs
 * {@code docker run -d}; Docker pulls the image first if it is not on the server.
 */
final class RunDialog extends VBox {

    private final TextField image = new TextField();
    private final TextField name = new TextField();
    private final TextField hostPort = new TextField();
    private final TextField containerPort = new TextField();
    private final Label status = new Label();
    private final Button run = new Button("RUN");
    private final Canvas pulling = new Canvas(400, 46);
    private final AnimationTimer animation;
    private double clock;

    /**
     * @param onRun    receives the image, the name and the port mappings; the second argument reports the outcome
     * @param onCancel closes the dialog
     */
    RunDialog(BiConsumer<RunRequest, java.util.function.Consumer<String>> onRun, Runnable onCancel) {
        getStyleClass().addAll("float-card", "run-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(12);
        setPadding(new Insets(24, 28, 24, 26));

        Label title = label("RUN A NEW CONTAINER", "card-title");
        Label note = label("KUBO RUNS docker run -d. IF THE IMAGE IS NOT ON THE SERVER, DOCKER PULLS IT FIRST — "
                + "THAT CAN TAKE A WHILE.", "card-note");
        note.setWrapText(true);
        note.setMaxWidth(400);
        note.setMinHeight(Region.USE_PREF_SIZE);

        image.getStyleClass().add("card-field");
        image.setPromptText("image, e.g. redis:alpine");
        name.getStyleClass().add("card-field");
        name.setPromptText("name (optional)");
        hostPort.getStyleClass().add("card-field");
        hostPort.setPromptText("host");
        hostPort.setPrefColumnCount(5);
        containerPort.getStyleClass().add("card-field");
        containerPort.setPromptText("container");
        containerPort.setPrefColumnCount(5);
        Label arrow = label("→", "mono-muted");
        HBox ports = new HBox(8, label("PORT", "field-label"), hostPort, arrow, containerPort);
        ports.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-problem");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);

        run.getStyleClass().addAll("primary-button", "compact-button");
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> onCancel.run());
        run.setOnAction(event -> submit(onRun));
        image.setOnAction(event -> submit(onRun));
        HBox buttons = new HBox(18, run, cancel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        pulling.setVisible(false);
        pulling.setManaged(false);
        getChildren().addAll(label("DOCKER", "series"), title, note,
                new VBox(4, label("IMAGE", "field-label"), image),
                new VBox(4, label("NAME", "field-label"), name), ports, pulling, status, buttons);
        image.textProperty().addListener((observable, previous, value) -> status.setText(""));
        Platform.runLater(image::requestFocus);

        animation = new AnimationTimer() {
            private long start = -1;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                clock = (now - start) / 1e9;
                drawPulling();
            }
        };
    }

    /**
     * The pull-and-start animation: a stream of layers flowing into a growing image block, endlessly, since a pull
     * can take a while.
     */
    private void drawPulling() {
        GraphicsContext gc = pulling.getGraphicsContext2D();
        double w = pulling.getWidth();
        double h = pulling.getHeight();
        gc.clearRect(0, 0, w, h);
        double cy = h / 2;
        Color cyan = Color.web("#35e8ff");
        Color teal = Color.web("#6ee0c8");

        // The image block on the right filling up in layers.
        double boxX = w - 60;
        double boxW = 44;
        gc.setStroke(cyan);
        gc.setLineWidth(1.2);
        gc.setGlobalAlpha(0.8);
        gc.strokeRect(boxX, cy - 16, boxW, 32);
        int layers = 6;
        double lit = (clock * 1.5) % (layers + 2);
        for (int i = 0; i < layers; i++) {
            gc.setFill(i < lit ? teal : Color.web("#1c1c24"));
            gc.setGlobalAlpha(i < lit ? 0.9 : 1);
            gc.fillRect(boxX + 3, cy + 13 - (i + 1) * 4.6, boxW - 6, 3.4);
        }

        // Particles streaming from the left into the block.
        for (int i = 0; i < 26; i++) {
            double t = (clock * 0.6 + i / 26.0 + (i * 7 % 5) * 0.03) % 1;
            double x = 4 + t * (boxX - 8);
            double y = cy + Math.sin(i * 1.7 + clock * 2) * 10 * (1 - t);
            double size = 1.4 + 2.2 * t;
            gc.setFill(i % 4 == 0 ? cyan : teal);
            gc.setGlobalAlpha(Math.sin(Math.PI * t) * 0.85);
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }

        gc.setGlobalAlpha(1);
        gc.setFill(Color.web("#8d8d9b"));
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("PULLING LAYERS…", 4, cy - 20 + 4);
    }

    record RunRequest(String image, String name, List<String> ports) {
    }

    private void submit(BiConsumer<RunRequest, java.util.function.Consumer<String>> onRun) {
        Optional<String> imageProblem = DockerCommands.imageProblem(image.getText());
        if (imageProblem.isPresent()) {
            setProblem(imageProblem.get());
            return;
        }
        List<String> ports = List.of();
        if (!hostPort.getText().isBlank() || !containerPort.getText().isBlank()) {
            Optional<String> mapping = DockerCommands.portMapping(hostPort.getText(), containerPort.getText());
            if (mapping.isEmpty()) {
                setProblem("The port must be two numbers between 1 and 65535.");
                return;
            }
            ports = List.of(mapping.get());
        }
        setBusy(true);
        onRun.accept(new RunRequest(image.getText().strip(), name.getText().strip(), ports), error -> {
            setBusy(false);
            if (error != null) {
                setProblem(error);
            }
        });
    }

    private void setProblem(String text) {
        status.getStyleClass().setAll("label", "card-problem");
        status.setText(text);
    }

    private void setBusy(boolean busy) {
        run.setDisable(busy);
        image.setDisable(busy);
        name.setDisable(busy);
        run.setText(busy ? "STARTING…" : "RUN");
        pulling.setVisible(busy);
        pulling.setManaged(busy);
        if (busy) {
            animation.start();
            status.getStyleClass().setAll("label", "card-note");
            status.setText("PULLING AND STARTING — THIS MAY TAKE A WHILE…");
        } else {
            animation.stop();
        }
    }

    /**
     * Stops the animation; call when the dialog closes.
     */
    void stopAnimation() {
        animation.stop();
    }

    /**
     * Shows the busy state on a fixed frame; for previews.
     */
    void previewBusy(double time, String imageText) {
        image.setText(imageText);
        run.setDisable(true);
        run.setText("STARTING…");
        status.getStyleClass().setAll("label", "card-note");
        status.setText("PULLING AND STARTING — THIS MAY TAKE A WHILE…");
        pulling.setVisible(true);
        pulling.setManaged(true);
        clock = time;
        drawPulling();
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
