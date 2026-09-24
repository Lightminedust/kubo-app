package com.kubo.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Writing a file, watched, and said to be done when it is.
 *
 * <p>Every export in Kubo used to be a menu, a pause, and then nothing. Whether the file had been written, how
 * big it was and where it went were all things somebody had to go and find out with a file manager. A save
 * that says nothing is a save nobody trusts, and on a slow disk it is indistinguishable from one that failed.
 *
 * <p>So it is the same shape as a download: a line that moves while it is happening, then the name, the size
 * and a way to go and look at it.
 */
public final class SaveCard extends VBox {

    /** Does the writing, off the interface thread. */
    public interface Writing {

        /**
         * @param file where it goes
         * @throws Exception whatever went wrong, which is shown as it is
         */
        void write(Path file) throws Exception;
    }

    private final Label status = new Label();
    private final Pulse pulse = new Pulse();
    private final Button close = new Button("✕");
    private final VBox done = new VBox(8);

    /**
     * @param what    what is being written, for the title: "THE BLOCKED ADDRESSES"
     * @param file    where it goes, already chosen
     * @param onClose called when the card is dismissed
     */
    public SaveCard(String what, Path file, Writing writing, java.util.concurrent.Executor worker,
                    Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setDisable(true);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("SAVING " + what, "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(640);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setText(file.toString());

        done.setVisible(false);
        done.setManaged(false);
        getChildren().addAll(header, pulse, status, done);

        pulse.setRunning(true);
        worker.execute(() -> {
            String trouble = null;
            long size = 0;
            try {
                writing.write(file);
                size = Files.isRegularFile(file) ? Files.size(file) : 0;
            } catch (Exception wrong) {
                trouble = wrong.getMessage() == null ? wrong.getClass().getSimpleName() : wrong.getMessage();
            }
            String said = trouble;
            long written = size;
            Platform.runLater(() -> finish(file, said, written, onClose));
        });
    }

    private void finish(Path file, String trouble, long size, Runnable onClose) {
        pulse.setRunning(false);
        close.setDisable(false);
        if (trouble != null) {
            status.getStyleClass().setAll("label", "card-problem");
            status.setText("Could not write it: " + trouble);
            return;
        }
        status.getStyleClass().setAll("label", "card-success");
        status.setText("Saved.  " + bytes(size) + "  ·  " + file);

        Button reveal = new Button("SHOW ME THE FILE");
        reveal.getStyleClass().addAll("rail-button", "compact-button");
        reveal.setFocusTraversable(false);
        reveal.setOnAction(event -> {
            try {
                com.kubo.platform.Host.reveal(file);
            } catch (java.io.IOException cannot) {
                status.getStyleClass().setAll("label", "card-problem");
                status.setText("Saved, but no file manager would open: " + cannot.getMessage());
            }
        });
        Button ok = new Button("DONE");
        ok.getStyleClass().addAll("primary-button", "compact-button");
        ok.setFocusTraversable(false);
        ok.setOnAction(event -> onClose.run());
        HBox row = new HBox(10, reveal, ok);
        row.setAlignment(Pos.CENTER_LEFT);
        done.getChildren().setAll(row);
        done.setVisible(true);
        done.setManaged(true);
    }

    /**
     * Asks where to put it, then writes it there.
     *
     * <p>Returns the card to show, or null when the chooser was dismissed, in which case nothing happens and
     * nothing is said: cancelling is an answer, not an error.
     */
    public static SaveCard ask(javafx.stage.Window over, String what, String suggestedName,
                               Writing writing, java.util.concurrent.Executor worker, Runnable onClose) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save " + what.toLowerCase(java.util.Locale.ROOT));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        chooser.setInitialFileName(suggestedName);
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads)) {
            chooser.setInitialDirectory(downloads.toFile());
        }
        java.io.File chosen = chooser.showSaveDialog(over);
        return chosen == null ? null : new SaveCard(what, chosen.toPath(), writing, worker, onClose);
    }

    /** A size in bytes, written the way the rest of Kubo writes sizes. */
    static String bytes(long value) {
        if (value >= 1024 * 1024) {
            return Math.round(value / (1024.0 * 1024) * 10) / 10.0 + " MB";
        }
        if (value >= 1024) {
            return Math.round(value / 1024.0) + " kB";
        }
        return value + " bytes";
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
