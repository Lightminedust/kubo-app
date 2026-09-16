package com.kubo.ui;

import com.kubo.command.CommandEntry;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootSession;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The button that asks to become root. It is the only way root is ever switched on: Kubo never elevates on its own.
 *
 * <p>Once root is in force the button says so, and clicking it again offers to leave root mode, which forgets the
 * sudo password at once.
 */
public final class RootButton extends Button {

    private final RootSession session;
    private final BiConsumer<Privilege.Command, Consumer<Boolean>> verify;
    private final Consumer<Region> showCard;
    private final Runnable hideCard;

    /**
     * @param verify   runs a command on the server and hands back its success on the UI thread
     * @param showCard puts a card over the screen; {@code hideCard} takes it away again
     */
    public RootButton(RootSession session, BiConsumer<Privilege.Command, Consumer<Boolean>> verify,
                      Consumer<Region> showCard, Runnable hideCard) {
        this.session = session;
        this.verify = verify;
        this.showCard = showCard;
        this.hideCard = hideCard;
        getStyleClass().add("root-button");
        setFocusTraversable(false);
        setMinWidth(Region.USE_PREF_SIZE);
        setOnAction(event -> clicked());
        RootSession.elevatedProperty().addListener((observable, before, value) -> update());
        update();
    }

    private void update() {
        boolean elevated = session != null && session.isElevated();
        setText(elevated ? "● ROOT" : "◌ ROOT");
        getStyleClass().remove("root-button-active");
        if (elevated) {
            getStyleClass().add("root-button-active");
        }
        setDisable(session == null || !session.isPossible());
        setTooltip(new javafx.scene.control.Tooltip(session == null ? ""
                : elevated ? "Root is on. Click to leave root mode and forget the password."
                : session.isPossible() ? "Ask for root for this session."
                : session.unavailableReason()));
    }

    private void clicked() {
        if (session == null || !session.isPossible()) {
            return;
        }
        if (session.isElevated()) {
            showCard.accept(new PromptCard("ROOT", "LEAVE ROOT MODE",
                    "Kubo will forget your sudo password and stop running anything as root. Type LEAVE to confirm.",
                    "LEAVE", "LEAVE ROOT", false, (typed, done) -> {
                if (!typed.strip().equalsIgnoreCase("LEAVE")) {
                    done.accept("Type LEAVE to confirm.");
                    return;
                }
                session.drop();
                update();
                hideCard.run();
            }, hideCard));
            return;
        }
        // Already root, or sudo without a password: nothing to ask, just say so.
        if (!session.needsPassword()) {
            session.refresh();
            update();
            showCard.accept(note("ROOT IS ALREADY YOURS",
                    session.access() == com.kubo.privilege.RootAccess.ROOT
                            ? "You are connected as root, so every command already runs with full rights."
                            : "This account may use sudo without a password, so Kubo can act as root when needed."));
            return;
        }
        showCard.accept(new com.kubo.ui.docker.SudoPrompt(session.privilege(), verify,
                () -> {
                    session.refresh();
                    update();
                    hideCard.run();
                },
                hideCard));
    }

    private Region note(String title, String text) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        Label body = new Label(text);
        body.getStyleClass().add("card-note");
        body.setWrapText(true);
        body.setMaxWidth(420);
        body.setMinHeight(Region.USE_PREF_SIZE);
        Button close = new Button("CLOSE");
        close.getStyleClass().add("ghost-button");
        close.setOnAction(event -> hideCard.run());
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(12,
                label("ROOT", "series"), heading, body, close);
        card.getStyleClass().addAll("float-card", "redis-prompt-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setPadding(new javafx.geometry.Insets(24, 28, 24, 26));
        Platform.runLater(close::requestFocus);
        return card;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /**
     * A wordmark that shows Kubo's name and marks it when root is in force.
     */
    public static Label wordmark() {
        Label label = new Label(RootSession.wordmark());
        label.getStyleClass().add("wordmark");
        RootSession.elevatedProperty().addListener((observable, before, value) -> {
            label.setText(RootSession.wordmark());
            label.getStyleClass().remove("wordmark-root");
            if (value) {
                label.getStyleClass().add("wordmark-root");
            }
        });
        if (RootSession.elevatedProperty().get()) {
            label.getStyleClass().add("wordmark-root");
        }
        return label;
    }

    /**
     * Verification that runs nothing, for screens with no connection of their own (previews).
     */
    public static BiConsumer<Privilege.Command, Consumer<Boolean>> noVerification() {
        return (command, done) -> done.accept(false);
    }

    /**
     * Turns a blocking check into the callback shape this button needs.
     */
    public static BiConsumer<Privilege.Command, Consumer<Boolean>> verifyWith(
            java.util.concurrent.Executor worker, java.util.function.Function<Privilege.Command, CommandEntry> run) {
        return (command, done) -> worker.execute(() -> {
            CommandEntry entry = run.apply(command);
            Platform.runLater(() -> done.accept(entry.success()));
        });
    }
}
