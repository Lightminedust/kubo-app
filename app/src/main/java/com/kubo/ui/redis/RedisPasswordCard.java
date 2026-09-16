package com.kubo.ui.redis;

import com.kubo.redis.RedisService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Asks for the password a Redis with {@code requirepass} wants.
 *
 * <p>The text never leaves this card as a String that could linger: it is read as a char array, handed straight to
 * the service, and wiped there once Redis has answered. Nothing is written to disk — unlike an SSH password, a
 * Redis password is never offered for saving.
 */
final class RedisPasswordCard extends VBox {

    RedisPasswordCard(List<RedisService.Detected> locked, String problem, Consumer<char[]> onSubmit) {
        getStyleClass().add("float-card");
        setSpacing(14);
        setPadding(new Insets(30, 34, 28, 32));
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setAlignment(Pos.CENTER_LEFT);

        Label kicker = label("REDIS.SCOPE", "series");
        Label heading = label("THIS REDIS ASKS FOR A PASSWORD", "host-key-host");
        heading.setWrapText(true);
        heading.setMaxWidth(520);

        String names = locked.stream().map(item -> item.status().where()).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("redis");
        Label note = label(("IT ANSWERED NOAUTH, WHICH MEANS requirepass IS SET ON " + names
                + ". KUBO KEEPS THE PASSWORD IN MEMORY FOR THIS SESSION ONLY, SENDS IT IN THE ENVIRONMENT OF EACH "
                + "redis-cli PROCESS RATHER THAN ON ITS COMMAND LINE, AND NEVER WRITES IT TO DISK OR TO THE LOG.")
                .toUpperCase(Locale.ROOT), "lede");
        note.setWrapText(true);
        note.setMaxWidth(520);

        PasswordField field = new PasswordField();
        field.getStyleClass().add("card-field");
        field.setPromptText("requirepass");
        field.setPrefColumnCount(26);

        Label failure = label(problem == null ? "" : problem.toUpperCase(Locale.ROOT), "card-problem");
        failure.setVisible(problem != null && !problem.isBlank());
        failure.setManaged(failure.isVisible());

        Button unlock = new Button("UNLOCK");
        unlock.getStyleClass().add("primary-button");
        unlock.setDefaultButton(true);
        Runnable submit = () -> {
            String typed = field.getText();
            if (typed == null || typed.isEmpty()) {
                failure.setText("TYPE THE PASSWORD REDIS EXPECTS.");
                failure.setVisible(true);
                failure.setManaged(true);
                return;
            }
            unlock.setDisable(true);
            field.setDisable(true);
            char[] password = typed.toCharArray();
            field.clear();
            onSubmit.accept(password);
        };
        unlock.setOnAction(event -> submit.run());
        field.setOnAction(event -> submit.run());

        HBox actions = new HBox(12, unlock);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(kicker, heading, note, field, failure, actions);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
