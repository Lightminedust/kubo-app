package com.kubo.ui.docker;

import com.kubo.privilege.Privilege;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Asks for the sudo password once, when a root action needs it. The password is checked with a harmless
 * {@code sudo true} before it is kept, and it never leaves memory.
 */
public final class SudoPrompt extends VBox {

    private final Privilege privilege;
    private final PasswordField field = new PasswordField();
    private final Label status = new Label();
    private final Button confirm = new Button("AUTHORISE");
    /** Runs under the field while sudo is being asked. A password check is a round trip, not an instant. */
    private final com.kubo.ui.Pulse pulse = new com.kubo.ui.Pulse();
    private final Runnable onCancel;

    /**
     * @param verify  runs a command and hands back its success on the UI thread (used to check the password)
     * @param onReady called once the password is accepted
     */
    public SudoPrompt(Privilege privilege, java.util.function.BiConsumer<Privilege.Command, Consumer<Boolean>> verify,
               Runnable onReady, Runnable onCancel) {
        this.privilege = privilege;
        this.onCancel = onCancel;
        getStyleClass().addAll("float-card", "sudo-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(12);
        setPadding(new Insets(22, 26, 22, 24));

        Label title = label("ROOT ACCESS NEEDED", "card-title");
        Label note = label("THIS ACTION RUNS AS ROOT THROUGH SUDO. TYPE YOUR SUDO PASSWORD — KUBO KEEPS IT IN MEMORY "
                + "FOR THIS SESSION ONLY, NEVER ON DISK.", "card-note");
        note.setWrapText(true);
        note.setMaxWidth(360);
        note.setMinHeight(Region.USE_PREF_SIZE);
        field.getStyleClass().add("card-field");
        field.setPromptText("sudo password");
        status.getStyleClass().add("card-problem");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);

        confirm.getStyleClass().addAll("primary-button", "compact-button");
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> onCancel.run());
        confirm.setOnAction(event -> submit(verify, onReady));
        field.setOnAction(event -> submit(verify, onReady));
        HBox buttons = new HBox(18, confirm, cancel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(label("SUDO", "series"), title, note, field, status, buttons);
        Platform.runLater(field::requestFocus);
    }

    private void submit(java.util.function.BiConsumer<Privilege.Command, Consumer<Boolean>> verify, Runnable onReady) {
        if (field.getText().isEmpty()) {
            return;
        }
        char[] password = field.getText().toCharArray();
        field.clear();
        privilege.setPassword(password);
        java.util.Arrays.fill(password, '\0');
        setBusy(true);
        verify.accept(privilege.verifyPassword(), ok -> {
            setBusy(false);
            if (ok) {
                onReady.run();
            } else {
                privilege.wipe();
                status.setText("WRONG PASSWORD, OR THIS USER MAY NOT SUDO. TRY AGAIN.");
                Platform.runLater(field::requestFocus);
            }
        });
    }

    private void setBusy(boolean busy) {
        confirm.setDisable(busy);
        field.setDisable(busy);
        pulse.setRunning(busy);
        confirm.setText(busy ? "CHECKING…" : "AUTHORISE");
        if (busy) {
            status.setText("");
        }
    }

    void cancel() {
        onCancel.run();
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /**
     * A cover that runs {@code action} once root is ready, asking for the password first if needed.
     */
    static void ensureRoot(Privilege privilege, Supplier<SudoPrompt> prompt, Runnable ifReady) {
        if (privilege.needsPassword()) {
            prompt.get();
        } else {
            ifReady.run();
        }
    }
}
