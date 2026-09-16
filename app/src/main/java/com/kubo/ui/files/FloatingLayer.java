package com.kubo.ui.files;

import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.files.BranchCanvas.MenuRequest;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.util.Duration;

/**
 * What floats over the tree: the action card placed where it was asked for and joined to that point by a thin
 * line, and the destination tree for a copy or a move. A click elsewhere closes the card without clicking the tree.
 */
final class FloatingLayer {

    private final FilesView view;
    private final StackPane map;
    private final BranchCanvas canvas;
    private final Pane overlay = new Pane();

    private ActionCard card;
    private DestinationPicker picker;
    private StackPane pickerLayer;
    // A press that only closed a card must not also click the tree.
    private boolean swallowClick;

    FloatingLayer(FilesView view, StackPane map, BranchCanvas canvas) {
        this.view = view;
        this.map = map;
        this.canvas = canvas;
        overlay.setPickOnBounds(false);
        // Cards float over the tree: their size must never make the tree area grow.
        overlay.setMinSize(0, 0);
        overlay.setPrefSize(0, 0);
        installDismissal();
    }

    /**
     * The pane cards are placed in, to stack over the tree.
     */
    Pane overlay() {
        return overlay;
    }

    ActionCard card() {
        return card;
    }

    DestinationPicker picker() {
        return picker;
    }

    // Cards

    void showCard(MenuRequest request) {
        closeCard();
        if (picker != null) {
            return;
        }
        card = new ActionCard(view, request);
        canvas.setMenuTarget(request);
        place(card, request.x(), request.y());
    }

    void closeCard() {
        if (card == null) {
            return;
        }
        overlay.getChildren().clear();
        card = null;
        canvas.setMenuTarget(null);
    }

    /**
     * Puts a card next to where it was asked for, inside the tree's bounds, joined to that point by a thin line.
     */
    private void place(Region node, double anchorX, double anchorY) {
        Line connector = new Line();
        connector.getStyleClass().add("card-connector");
        Circle anchor = new Circle(3);
        anchor.getStyleClass().add("card-anchor");
        overlay.getChildren().setAll(connector, anchor, node);

        Runnable position = () -> {
            double width = node.prefWidth(-1);
            double height = node.prefHeight(width);
            double spaceWidth = overlay.getWidth() > 0 ? overlay.getWidth() : map.getWidth();
            double spaceHeight = overlay.getHeight() > 0 ? overlay.getHeight() : map.getHeight();
            double x = anchorX + 34;
            if (x + width > spaceWidth - 12) {
                x = anchorX - 34 - width;
            }
            x = BranchStyle.clamp(x, 12, Math.max(12, spaceWidth - width - 12));
            // Below the column headers when it fits; a card taller than the tree uses its whole height.
            double top = height + 108 <= spaceHeight ? 96 : 12;
            double y = BranchStyle.clamp(anchorY - 40, top, Math.max(top, spaceHeight - height - 12));
            node.resizeRelocate(x, y, width, height);
            boolean right = x > anchorX;
            connector.setStartX(anchorX);
            connector.setStartY(anchorY);
            connector.setEndX(right ? x : x + width);
            connector.setEndY(BranchStyle.clamp(anchorY, y + 16, y + Math.max(16, height - 16)));
            anchor.setCenterX(anchorX);
            anchor.setCenterY(anchorY);
        };
        node.applyCss();
        position.run();
        node.needsLayoutProperty().addListener((observable, previous, needs) -> {
            if (!needs) {
                position.run();
            }
        });
        node.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> Platform.runLater(position));

        node.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), node);
        fadeIn.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), node);
        slide.setFromY(8);
        slide.setToY(0);
        new ParallelTransition(fadeIn, slide).play();
    }

    // Destination tree

    /**
     * Opens the destination tree for a copy or a move, over the tree.
     */
    void openPicker(RemoteEntry entry, RemoteEntry folder, boolean move, String homePath, boolean showHidden) {
        closeCard();
        closePicker();
        picker = new DestinationPicker(view, entry, folder, move, homePath, showHidden);
        picker.maxWidthProperty().bind(map.widthProperty().multiply(0.92));
        picker.maxHeightProperty().bind(map.heightProperty().multiply(0.9));
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        pickerLayer = new StackPane(scrim, picker);
        pickerLayer.setMinSize(0, 0);
        pickerLayer.setPrefSize(0, 0);
        map.getChildren().add(pickerLayer);
        fade(pickerLayer, true);
        Platform.runLater(picker.tree()::requestFocus);
    }

    void closePicker() {
        if (picker == null) {
            return;
        }
        map.getChildren().remove(pickerLayer);
        picker.shutdown();
        picker = null;
        pickerLayer = null;
        canvas.requestFocus();
    }

    private void installDismissal() {
        map.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            swallowClick = false;
            if (card != null && !contains(card, event.getSceneX(), event.getSceneY())) {
                closeCard();
                // A right-click goes through, so it opens the card of what is under it right away.
                if (event.isPrimaryButtonDown()) {
                    swallowClick = true;
                    event.consume();
                }
            }
        });
        map.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (swallowClick) {
                event.consume();
            }
        });
        map.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (swallowClick) {
                event.consume();
            }
        });
        map.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (swallowClick) {
                swallowClick = false;
                event.consume();
            }
        });
        map.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (card != null && !contains(card, event.getSceneX(), event.getSceneY())) {
                closeCard();
            }
        });
    }

    static boolean contains(Node node, double sceneX, double sceneY) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds.contains(sceneX, sceneY);
    }

    static void fade(Node node, boolean in) {
        FadeTransition transition = new FadeTransition(Duration.millis(in ? 160 : 400), node);
        if (in) {
            node.setOpacity(0);
            node.setVisible(true);
        }
        transition.setToValue(in ? 1 : 0);
        if (!in) {
            transition.setOnFinished(event -> node.setVisible(false));
        }
        transition.play();
    }
}
