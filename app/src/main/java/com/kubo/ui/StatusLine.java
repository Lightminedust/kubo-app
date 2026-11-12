package com.kubo.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The line along the bottom of a screen, the way an editor keeps one: a block that says what state things
 * are in, what is happening in words, and a few facts on the right.
 *
 * <p>It replaces status text scattered next to whichever button caused it. One place to look, always the
 * same place, on every screen that has one.
 */
public final class StatusLine extends HBox {

    /** What the block on the left is saying, which decides its colour. */
    public enum Mode { IDLE, BUSY, OK, ERROR }

    private final Label mode = new Label("READY");
    private final Label message = new Label();
    private final HBox facts = new HBox(0);

    public StatusLine() {
        getStyleClass().add("status-line");
        setAlignment(Pos.CENTER_LEFT);
        mode.getStyleClass().addAll("status-mode", "status-mode-idle");
        mode.setMinWidth(Region.USE_PREF_SIZE);
        message.getStyleClass().add("status-message");
        message.setMinWidth(0);
        HBox.setHgrow(message, Priority.ALWAYS);
        message.setMaxWidth(Double.MAX_VALUE);
        facts.setAlignment(Pos.CENTER_RIGHT);
        facts.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(mode, message, facts);
    }

    public void show(Mode state, String word, String text) {
        mode.setText(word);
        mode.getStyleClass().removeAll("status-mode-idle", "status-mode-busy", "status-mode-ok",
                "status-mode-error");
        mode.getStyleClass().add(switch (state) {
            case IDLE -> "status-mode-idle";
            case BUSY -> "status-mode-busy";
            case OK -> "status-mode-ok";
            case ERROR -> "status-mode-error";
        });
        message.setText(text == null ? "" : text);
    }

    /** The message alone, for a screen that drives it from somewhere else. */
    public Label message() {
        return message;
    }

    /** Adds one fact to the right-hand side, separated the way a statusline separates them. */
    public Label fact(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-fact");
        label.setMinWidth(Region.USE_PREF_SIZE);
        facts.getChildren().add(label);
        return label;
    }

    public void fact(Node node) {
        facts.getChildren().add(node);
    }
}
