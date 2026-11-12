package com.kubo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * A card asking before something is done to the server, in the look of the tile screens.
 *
 * <p>It says what will happen in a sentence. When the thing cannot be taken back, it also asks for a name
 * to be typed back before its button does anything: a click is easy to give by mistake, a name is not.
 * The answer comes back on the card itself, so a refusal from the server is read where it was asked for.
 */
public final class ConfirmCard extends VBox {

    private final Label problem = new Label();
    private final Button go;
    private final TextField field = new TextField();

    /**
     * @param expected what has to be typed before the button works, or null when a click is enough
     * @param danger   paints the button red, for what takes something away
     * @param onGo     receives a callback to call with null once it is done, or with what went wrong
     */
    public ConfirmCard(String question, String consequence, String expected, String goLabel, boolean danger,
                       Consumer<Consumer<String>> onGo, Runnable onCancel) {
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(16);
        setPadding(new Insets(22, 26, 20, 26));
        setPrefWidth(480);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label(question);
        title.getStyleClass().add("fetch-host");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        Label words = new Label(consequence);
        words.getStyleClass().add("home-muted");
        words.setWrapText(true);
        words.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(title, words);

        go = new Button(goLabel);
        go.getStyleClass().addAll("ghost-button", danger ? "port-danger" : "apps-lit");
        go.setFocusTraversable(false);
        go.setMinWidth(Region.USE_PREF_SIZE);
        if (expected != null) {
            Label key = new Label("type " + expected);
            key.getStyleClass().add("fetch-key");
            key.setMinWidth(Region.USE_PREF_SIZE);
            field.getStyleClass().add("card-field");
            field.setPromptText(expected);
            HBox.setHgrow(field, Priority.ALWAYS);
            HBox typing = new HBox(14, key, field);
            typing.setAlignment(Pos.CENTER_LEFT);
            getChildren().add(typing);
            go.setDisable(true);
            field.textProperty().addListener((observable, before, now) ->
                    go.setDisable(!now.strip().equals(expected)));
            field.setOnAction(event -> {
                if (!go.isDisable()) {
                    go.fire();
                }
            });
        }

        Button cancel = new Button("cancel");
        cancel.getStyleClass().add("ghost-button");
        cancel.setFocusTraversable(false);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setOnAction(event -> onCancel.run());
        problem.getStyleClass().add("card-problem");
        problem.setWrapText(true);
        problem.setMinHeight(Region.USE_PREF_SIZE);
        problem.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(problem, Priority.ALWAYS);
        HBox buttons = new HBox(16, problem, cancel, go);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(buttons);

        String label = goLabel;
        go.setOnAction(event -> {
            go.setDisable(true);
            field.setDisable(true);
            go.setText("working…");
            problem.setText("");
            onGo.accept(trouble -> {
                go.setText(label);
                if (trouble != null) {
                    go.setDisable(false);
                    field.setDisable(false);
                    problem.setText(trouble);
                }
            });
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onCancel.run();
                event.consume();
            }
        });
        sceneProperty().addListener((observable, before, scene) -> {
            if (scene != null && expected != null) {
                javafx.application.Platform.runLater(field::requestFocus);
            }
        });
    }

    /** A card that only says why something will not be done, and closes. */
    public static VBox refusal(String title, String reason, Runnable onClose) {
        Label heading = new Label(title);
        heading.getStyleClass().add("fetch-host");
        heading.setWrapText(true);
        heading.setMinHeight(Region.USE_PREF_SIZE);
        Label words = new Label(reason);
        words.getStyleClass().add("fetch-note");
        words.setWrapText(true);
        words.setMinHeight(Region.USE_PREF_SIZE);
        Button close = new Button("close");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(16, heading, words, buttons);
        card.getStyleClass().addAll("float-card", "redis-help");
        card.setPadding(new Insets(22, 26, 20, 26));
        card.setPrefWidth(480);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        return card;
    }
}
