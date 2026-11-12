package com.kubo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * One pane of a tiled terminal: a thin frame with its name written into the top edge.
 *
 * <p>The name sits on the line rather than under it, the way tmux and every window manager people rice
 * their desktops with draw it. That one detail is most of what makes a screen read as a terminal instead of
 * as a web page with dark colours: no heading row, no rule under a heading, nothing spent on saying where a
 * thing starts except the frame itself.
 */
public final class Tile extends StackPane {

    private final Label name = new Label();
    private final Label tag = new Label();
    private final VBox body = new VBox();

    public Tile(String title, Node content) {
        getStyleClass().add("tile");
        body.getStyleClass().add("tile-body");
        body.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        name.setText(title);
        name.getStyleClass().add("tile-name");
        tag.getStyleClass().add("tile-tag");
        tag.setVisible(false);
        tag.setManaged(false);

        // Both labels are lifted onto the frame and carry the page's own colour behind them, which is what
        // cuts the line where the words are.
        HBox edge = new HBox(name, new javafx.scene.layout.Region(), tag);
        HBox.setHgrow(edge.getChildren().get(1), Priority.ALWAYS);
        edge.setAlignment(Pos.CENTER_LEFT);
        edge.setPickOnBounds(false);
        edge.setMaxHeight(USE_PREF_SIZE);
        edge.setTranslateY(-9);
        StackPane.setAlignment(edge, Pos.TOP_LEFT);
        StackPane.setMargin(edge, new Insets(0, 14, 0, 14));

        getChildren().addAll(body, edge);
        setMinWidth(0);
    }

    /** A short note on the right of the top edge: a count, a state. Empty hides it. */
    public void setTag(String text) {
        boolean shown = text != null && !text.isBlank();
        tag.setText(shown ? text : "");
        tag.setVisible(shown);
        tag.setManaged(shown);
    }

    /** Colours the frame's name: "tile-cyan", "tile-violet", "tile-amber", "tile-green", or none for red. */
    public Tile accent(String styleClass) {
        getStyleClass().add(styleClass);
        return this;
    }
}
