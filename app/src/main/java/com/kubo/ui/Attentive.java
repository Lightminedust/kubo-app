package com.kubo.ui;

import com.kubo.command.Attention;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * Ties a window to a screen name, so {@link Attention} knows which screen the operator is on.
 *
 * <p>Kept apart from {@link Attention} itself so the rule stays a plain piece of logic with no window in it.
 */
public final class Attentive {

    private static final String BOUND = "kubo.attention.scope";

    private Attentive() {
    }

    public static void bind(Stage stage, String scope) {
        bind(stage, scope, () -> { });
    }

    /**
     * @param onResume runs when this window comes back to the front after another one had it, so the screen can
     *                 catch up on what it did not read while it was behind
     */
    public static void bind(Stage stage, String scope, Runnable onResume) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onResume, "onResume");
        // A window may be shown, hidden and shown again; it is bound once and keeps its listeners.
        if (stage.getProperties().put(BOUND, scope) != null) {
            Attention.claim(scope);
            return;
        }
        stage.focusedProperty().addListener((observable, before, focused) -> {
            if (!Boolean.TRUE.equals(focused)) {
                return;
            }
            boolean returning = !Attention.active().equals(scope);
            Attention.claim(scope);
            if (returning) {
                onResume.run();
            }
        });
        stage.showingProperty().addListener((observable, before, showing) -> {
            if (Boolean.TRUE.equals(showing)) {
                Attention.claim(scope);
            } else if (scope.equals(Attention.active())) {
                // Closed, and it was the one in front. Left as it was, every other screen would wait for a window
                // that no longer exists. Nobody holds it now, so they all read until the next one is focused.
                Attention.claim("");
            }
        });
        if (stage.isShowing()) {
            Attention.claim(scope);
        }
    }
}
