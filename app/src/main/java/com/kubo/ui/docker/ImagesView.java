package com.kubo.ui.docker;

import com.kubo.model.docker.DockerImage;
import com.kubo.ui.scope.Format;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.function.Consumer;

/**
 * The images on disk: their size, when they were made, and which are dangling leftovers that can be swept away.
 */
final class ImagesView extends VBox {

    private final VBox rows = new VBox(2);
    private final Label summary = label("", "metric-label");
    private final Consumer<DockerImage> onRemove;
    private final Runnable onPruneDangling;

    ImagesView(Consumer<DockerImage> onRemove, Runnable onPruneDangling) {
        this.onRemove = onRemove;
        this.onPruneDangling = onPruneDangling;
        getStyleClass().add("scope-view");
        setPadding(new Insets(24, 40, 24, 40));
        setSpacing(14);

        Label title = label("IMAGES", "series");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button prune = new Button("SWEEP DANGLING");
        prune.getStyleClass().add("ghost-button");
        prune.setOnAction(event -> onPruneDangling.run());
        HBox header = new HBox(16, title, summary, spacer, prune);
        header.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);
    }

    /**
     * Clears the list and shows that it is being read again.
     */
    void showLoading() {
        rows.getChildren().setAll(label("READING IMAGES…", "metric-label"));
        summary.setText("");
    }

    void setImages(List<DockerImage> images) {
        rows.getChildren().clear();
        long total = images.stream().mapToLong(DockerImage::sizeBytes).sum();
        long dangling = images.stream().filter(DockerImage::dangling).count();
        summary.setText(images.size() + " IMAGES  ·  " + Format.bytes(total)
                + (dangling > 0 ? "  ·  " + dangling + " DANGLING" : ""));
        if (images.isEmpty()) {
            rows.getChildren().add(label("NO IMAGES ON THIS SERVER.", "metric-label"));
            return;
        }
        for (DockerImage image : images) {
            rows.getChildren().add(row(image));
        }
    }

    private HBox row(DockerImage image) {
        Circle dot = new Circle(4, image.dangling() ? Color.web("#ff5a6e") : Color.web("#5b6fd6"));
        Label reference = label(image.reference(), image.dangling() ? "mono-muted" : "mono");
        reference.setMinWidth(0);
        HBox.setHgrow(reference, Priority.ALWAYS);
        reference.setMaxWidth(Double.MAX_VALUE);
        Label id = label(image.shortId(), "mono-muted");
        id.setMinWidth(90);
        Label created = label(image.createdSince(), "metric-label");
        created.setMinWidth(120);
        Label size = label(Format.bytes(image.sizeBytes()), "metric-value");
        size.setMinWidth(80);
        Button remove = new Button("✕");
        remove.getStyleClass().add("upload-close");
        remove.setFocusTraversable(false);
        remove.setOnAction(event -> onRemove.accept(image));

        HBox row = new HBox(14, dot, reference, id, created, size, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("image-row");
        return row;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
