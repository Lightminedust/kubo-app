package com.kubo.ui.files;

import com.kubo.files.FileCommands;
import com.kubo.model.files.Account;
import com.kubo.model.files.RemoteEntry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Locale;
import java.util.Optional;

/**
 * Choosing where a copy or a move goes, visually: a tree of folders of its own, opened on the folder the entry
 * is in, where the user goes forward and back and clicks the destination.
 */
final class DestinationPicker extends VBox {

    private final FilesView view;
    private final RemoteEntry source;
    private final RemoteEntry sourceFolder;
    private final boolean move;
    private final Account account;
    private final BranchCanvas tree = new BranchCanvas();

    private final Label destinationLabel = label("—", "picker-destination");
    private final Label resultLabel = label("", "mono-muted");
    private final Label status = label("", "card-note");
    private final Button confirm = new Button();
    private final Button cancel = new Button("CANCEL");
    private final Timeline working;

    private RemoteEntry destination;
    private boolean busy;

    DestinationPicker(FilesView view, RemoteEntry source, RemoteEntry sourceFolder, boolean move, String homePath,
                      boolean showHidden) {
        this.view = view;
        this.source = source;
        this.sourceFolder = sourceFolder;
        this.move = move;
        this.account = view.account();
        getStyleClass().add("picker-card");

        String verb = move ? "MOVE" : "COPY";
        Label kicker = label(verb + " TO…", "series");
        Label title = label("Where should " + ActionCard.displayName(source) + " go?", "files-title");
        Label hint = label("THE TREE OPENS WHERE " + ActionCard.displayName(source).toUpperCase(Locale.ROOT)
                + " IS  ·  DOUBLE-CLICK A FOLDER TO GO FORWARD  ·  BACK OR BACKSPACE TO GO BACK  ·  "
                + "CLICK A FOLDER TO CHOOSE IT  ·  DRAG, WHEEL AND CTRL+WHEEL AS IN THE MAIN TREE", "lede");
        hint.setWrapText(true);
        VBox heading = new VBox(4, kicker, title, hint);
        HBox.setHgrow(heading, Priority.ALWAYS);

        Button back = ghost("← BACK", () -> tree.back());
        Button home = ghost("⌂ HOME", tree::goHome);
        Button here = ghost("◎ START", () -> tree.showPath(homePath, sourceFolder.path()));
        Button fit = ghost("FIT", tree::fit);
        HBox navigation = new HBox(18, here, home, back, fit);
        navigation.setAlignment(Pos.TOP_RIGHT);
        HBox header = new HBox(24, heading, navigation);
        header.setPadding(new Insets(18, 26, 8, 24));

        tree.setMinSize(0, 0);
        VBox.setVgrow(tree, Priority.ALWAYS);

        confirm.getStyleClass().addAll("primary-button", "compact-button");
        confirm.setText(verb + " HERE");
        confirm.setOnAction(event -> confirm());
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> view.closePicker());
        status.setWrapText(true);
        destinationLabel.setWrapText(true);
        resultLabel.setWrapText(true);
        VBox chosen = new VBox(3, label("DESTINATION", "metric-label"), destinationLabel, resultLabel, status);
        HBox.setHgrow(chosen, Priority.ALWAYS);
        HBox footer = new HBox(22, chosen, cancel, confirm);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("picker-footer");
        footer.setPadding(new Insets(14, 26, 16, 24));

        getChildren().addAll(header, tree, footer);

        String[] frames = {"", ".", "..", "..."};
        int[] frame = {0};
        working = new Timeline(new KeyFrame(Duration.millis(300), event ->
                status.setText((move ? "MOVING" : "COPYING") + frames[frame[0]++ % frames.length])));
        working.setCycleCount(Timeline.INDEFINITE);

        tree.setOnOpen(directory -> view.load(tree, directory));
        tree.setOnSelect(this::choose);
        tree.setOnNavigate(directory -> {
            RemoteEntry selected = tree.selectedEntry();
            if (selected == null || !selected.isDirectory()) {
                choose(directory);
            }
        });
        tree.setFilter(false, showHidden || sourceFolder.path().contains("/."));
        tree.showPath(homePath, sourceFolder.path());
        choose(sourceFolder);
    }

    BranchCanvas tree() {
        return tree;
    }

    void back() {
        if (!busy) {
            tree.back();
        }
    }

    boolean busy() {
        return busy;
    }

    void shutdown() {
        tree.stop();
        working.stop();
    }

    private void choose(RemoteEntry folder) {
        if (busy) {
            return;
        }
        if (!folder.isDirectory()) {
            destinationLabel.setText(folder.path());
            resultLabel.setText("");
            setStatus(folder.type() == RemoteEntry.Type.LINK
                    ? "THIS IS A LINK: OPEN THE FOLDER IT POINTS TO INSTEAD" : "CHOOSE A FOLDER", true);
            confirm.setDisable(true);
            return;
        }
        destination = folder;
        String target = move ? RemoteEntry.join(folder.path(), source.name())
                : FileCommands.destinationPath(source.path(), folder.path());
        destinationLabel.setText(folder.path());
        resultLabel.setText("→  " + target);

        // Root mode lifts the user's own permissions: the move or copy will run as root, so a destination they
        // could not write to on their own is still a valid target.
        boolean gated = account != null && !view.elevated();
        Optional<String> problem = FileCommands.destinationProblem(source.path(), folder.path(), move)
                .or(() -> !view.elevated() && !folder.readable()
                        ? Optional.of("No permission to enter this folder") : Optional.empty())
                .or(() -> gated && !account.canCreateIn(folder)
                        ? Optional.of("No write permission in " + ActionCard.displayName(folder)) : Optional.empty());
        if (problem.isPresent()) {
            setStatus(problem.get().toUpperCase(Locale.ROOT), true);
        } else if (folder.path().equals(sourceFolder.path())) {
            setStatus("A COPY NEXT TO THE ORIGINAL, UNDER A NEW NAME", false);
        } else {
            setStatus("", false);
        }
        confirm.setDisable(problem.isPresent());
    }

    private void confirm() {
        if (destination == null || busy) {
            return;
        }
        RemoteEntry folder = destination;
        String target = FileCommands.destinationPath(source.path(), folder.path());
        busy = true;
        confirm.setDisable(true);
        cancel.setDisable(true);
        setStatus("", false);
        working.play();
        view.run(() -> move ? view.browser().move(source.path(), target) : view.browser().copy(source.path(), target),
                result -> {
                    busy = false;
                    working.stop();
                    if (result.success()) {
                        view.closePicker();
                        if (move) {
                            view.forget(source.path());
                        }
                        view.changed(target, folder.path(), move ? sourceFolder.path() : null);
                        view.notice((move ? "MOVED  " : "COPIED  ") + source.path() + "  →  " + target, false);
                    } else {
                        setStatus(FileCommands.problem(result), true);
                        confirm.setDisable(false);
                        cancel.setDisable(false);
                    }
                });
    }

    private void setStatus(String text, boolean problem) {
        status.getStyleClass().setAll("label", problem ? "card-problem" : "card-note");
        status.setText(text);
    }

    private static Button ghost(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
