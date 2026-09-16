package com.kubo.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;

/**
 * A small Kubo card that either asks for one value before doing something, or shows the output of a command.
 *
 * <p>The asking form is what stands between the user and anything irreversible: it states plainly what will happen
 * and makes them type the name of the thing back before the button does anything.
 */
public final class PromptCard extends VBox {

    /**
     * @param danger   styles the card as a warning and the button as destructive
     * @param onSubmit receives what was typed and a callback taking an error message, or null on success
     */
    public PromptCard(String series, String title, String note, String prompt, String action, boolean danger,
                      BiConsumer<String, java.util.function.Consumer<String>> onSubmit, Runnable onCancel) {
        getStyleClass().addAll("float-card", "redis-prompt-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPadding(new Insets(24, 28, 24, 26));
        setSpacing(12);
        Label explanation = label(note, danger ? "card-problem" : "card-note");
        explanation.setWrapText(true);
        explanation.setMaxWidth(440);
        explanation.setMinHeight(Region.USE_PREF_SIZE);
        TextField input = new TextField();
        input.getStyleClass().add("card-field");
        input.setPromptText(prompt);
        Label status = label("", "card-problem");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        Button submit = new Button(action);
        submit.getStyleClass().addAll(danger ? "danger-button" : "primary-button", "compact-button");
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> onCancel.run());
        Runnable send = () -> {
            String value = input.getText().strip();
            if (value.isEmpty()) {
                status.setText("A value is required.");
                return;
            }
            submit.setDisable(true);
            input.setDisable(true);
            submit.setText("WORKING…");
            onSubmit.accept(value, error -> {
                submit.setDisable(false);
                input.setDisable(false);
                submit.setText(action);
                if (error != null) {
                    status.setText(error);
                }
            });
        };
        submit.setOnAction(event -> send.run());
        input.setOnAction(event -> send.run());
        HBox buttons = new HBox(16, submit, cancel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(label(series, "series"), label(title, "card-title"), explanation, input, status, buttons);
        Platform.runLater(input::requestFocus);
    }

    /**
     * A card showing the plain output of a command, such as a slowlog or a failed configuration test.
     */
    public static PromptCard output(String series, String title, String text, Runnable onClose) {
        PromptCard card = new PromptCard();
        card.getStyleClass().addAll("float-card", "redis-output-card");
        card.setMaxSize(760, 570);
        card.setPadding(new Insets(24, 28, 24, 26));
        card.setSpacing(12);
        TextArea output = new TextArea(text == null || text.isBlank() ? "NO ENTRIES" : text);
        output.getStyleClass().add("redis-diagnostic-output");
        output.setEditable(false);
        output.setWrapText(false);
        output.setPrefSize(700, 430);
        Button close = new Button("CLOSE");
        close.getStyleClass().add("ghost-button");
        close.setOnAction(event -> onClose.run());
        card.getChildren().addAll(label(series, "series"), label(title, "card-title"), output, close);
        return card;
    }

    private PromptCard() {
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
