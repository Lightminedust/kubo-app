package com.kubo.ui.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.FileCommands;
import com.kubo.model.files.Account;
import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.files.BranchCanvas.MenuRequest;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The floating card opened on an entry (right-click) or on a folder's space (click): its actions first,
 * then the form of the chosen action in the same place. Actions the user cannot do stay listed with the reason.
 */
final class ActionCard extends VBox {

    private static final double WIDTH = 300;
    private static final double WIDE = 690;

    private final FilesView view;
    private final RemoteEntry entry;
    private final RemoteEntry folder;
    private final Account account;

    ActionCard(FilesView view, MenuRequest request) {
        this.view = view;
        this.entry = request.entry();
        this.folder = request.folder();
        this.account = view.account();
        if (entry != null) {
            showEntryMenu();
        } else {
            showFolderMenu();
        }
    }

    boolean onEntry() {
        return entry != null;
    }

    // Menus

    void showEntryMenu() {
        RemoteEntry subject = entry;
        String removal = removalProblem(subject);
        List<Node> items = new ArrayList<>();
        items.add(header(subject, typeName(subject)));
        if (subject.isDirectory()) {
            items.add(action("OPEN", "DOUBLE-CLICK", enterProblem(subject), false, () -> {
                view.closeCard();
                view.openFolder(subject);
            }));
        } else {
            // A picture, a sound or a film is offered to the viewer first. An editor opening an MP4 shows a
            // page of bytes, and the first thing it would do is offer to save them back.
            java.util.Optional<com.kubo.model.media.MediaKind> shows =
                    com.kubo.model.media.MediaKind.playable(subject.name());
            java.util.Optional<com.kubo.model.media.MediaKind> media =
                    com.kubo.model.media.MediaKind.of(subject.name());
            if (shows.isPresent()) {
                com.kubo.model.media.MediaKind kind = shows.get();
                items.add(action(kind == com.kubo.model.media.MediaKind.IMAGE ? "VIEW" : "PLAY", "ENTER",
                        enterProblem(subject), false, () -> {
                            view.closeCard();
                            view.view(subject, kind);
                        }));
            } else if (media.isPresent()) {
                // Named rather than silent. Somebody who right-clicks a .mkv and finds no PLAY has learnt
                // nothing; told there is no decoder for it, they know to download it.
                items.add(action("PLAY", "",
                        com.kubo.model.media.MediaKind.whyNot(subject.name()).orElse(null), false, () -> { }));
            }
            boolean writable = account == null || view.elevated() || account.canWrite(subject);
            items.add(action(writable ? "EDIT" : "VIEW", "", editProblem(subject), false, () -> {
                view.closeCard();
                view.edit(subject);
            }));
        }
        items.add(action("DOWNLOAD", "⇩", downloadProblem(subject), false, () -> showDownload(subject)));
        items.add(action("RENAME", "F2", removal, false, this::showRename));
        items.add(action("COPY TO…", "", copyProblem(subject), false, () -> view.transfer(subject, folder, false)));
        items.add(action("MOVE TO…", "", removal, false, () -> view.transfer(subject, folder, true)));
        items.add(action("PERMISSIONS", "", modeProblem(subject), false, () -> showPermissions(subject)));
        items.add(rule());
        items.add(action("DELETE", "DEL", removal, true, this::showDelete));
        setContent(WIDTH, false, 2, items);
    }

    void showFolderMenu() {
        RemoteEntry subject = folder;
        String create = !view.elevated() && !subject.readable() ? "NO PERMISSION TO ENTER THIS FOLDER"
                : gated() && !account.canCreateIn(subject) ? "NO WRITE PERMISSION IN THIS FOLDER" : null;
        List<Node> items = new ArrayList<>();
        items.add(header(subject, "FOLDER SPACE"));
        items.add(action("NEW FILE", "", create, false, () -> showCreate(false)));
        items.add(action("NEW FOLDER", "", create, false, () -> showCreate(true)));
        items.add(action("PERMISSIONS", "", modeProblem(subject), false, () -> showPermissions(subject)));
        items.add(action("DOWNLOAD THIS FOLDER", "⇩", downloadProblem(subject), false, () -> showDownload(subject)));
        items.add(rule());
        items.add(action("READ AGAIN", "", null, false, () -> {
            view.closeCard();
            view.changed(null, subject.path());
        }));
        setContent(WIDTH, false, 2, items);
    }

    // Forms

    void showRename() {
        TextField field = field(entry.name(), "new name");
        Label status = status();
        Button apply = primary("RENAME");
        Button back = ghost("BACK", this::showEntryMenu);
        Runnable check = () -> {
            Optional<String> problem = FileCommands.nameProblem(field.getText());
            setStatus(status, problem.orElse(""), "card-problem");
            apply.setDisable(problem.isPresent() || field.getText().equals(entry.name()));
        };
        field.textProperty().addListener((observable, previous, text) -> check.run());
        check.run();

        apply.setOnAction(event -> {
            String name = field.getText();
            String target = RemoteEntry.join(folder.path(), name);
            run("RENAMING…", status, List.of(apply, back, field), () -> view.browser().move(entry.path(), target), () -> {
                view.closeCard();
                view.forget(entry.path());
                view.changed(target, folder.path());
                view.notice("RENAMED  " + entry.name() + "  →  " + name, false);
            });
        });
        field.setOnAction(event -> apply.fire());

        setContent(WIDTH, false, 10, List.of(title("RENAME", entry.path(), false), field, status, buttons(apply, back)));
        int dot = entry.isDirectory() ? -1 : entry.name().lastIndexOf('.');
        focus(field, dot > 0 ? dot : entry.name().length());
    }

    void showDelete() {
        RemoteEntry subject = entry;
        boolean confirmByName = subject.isDirectory() && subject.childCount() != 0;
        Label what = note(deletionScope(subject));
        Label warning = label("A SERVER HAS NO TRASH: THIS CANNOT BE UNDONE.", "card-problem");
        warning.setWrapText(true);
        TextField confirm = field("", "type " + subject.name() + " to confirm");
        Label status = status();
        Button delete = new Button("DELETE");
        delete.getStyleClass().add("danger-button");
        Button back = ghost("BACK", this::showEntryMenu);

        List<Node> children = new ArrayList<>(List.of(title("DELETE PERMANENTLY", subject.path(), true), what, warning));
        if (confirmByName) {
            children.add(confirm);
            delete.disableProperty().bind(confirm.textProperty().isNotEqualTo(subject.name()));
            confirm.setOnAction(event -> delete.fire());
        }
        children.add(buttons(delete, back));
        children.add(status);

        delete.setOnAction(event -> {
            delete.disableProperty().unbind();
            run("DELETING…", status, List.of(delete, back, confirm), () -> view.browser().delete(subject), () -> {
                view.closeCard();
                view.forget(subject.path());
                view.changed(null, folder.path());
                view.notice("DELETED  " + subject.path(), false);
            });
        });
        setContent(WIDTH, true, 10, children);
        focus(confirmByName ? confirm : back, -1);
    }

    void showCreate(boolean directory) {
        TextField field = field("", directory ? "folder name" : "file-name.txt");
        Label status = status();
        Button create = primary("CREATE");
        Button back = ghost("BACK", this::showFolderMenu);
        Runnable check = () -> {
            Optional<String> problem = FileCommands.nameProblem(field.getText());
            setStatus(status, field.getText().isEmpty() ? "" : problem.orElse(""), "card-problem");
            create.setDisable(problem.isPresent());
        };
        field.textProperty().addListener((observable, previous, text) -> check.run());
        check.run();

        create.setOnAction(event -> {
            String name = field.getText();
            String path = RemoteEntry.join(folder.path(), name);
            Supplier<CommandEntry> action = directory
                    ? () -> view.browser().createFolder(path)
                    : () -> view.browser().createFile(path);
            run("CREATING…", status, List.of(create, back, field), action, () -> {
                view.closeCard();
                view.changed(path, folder.path());
                view.notice("CREATED  " + path, false);
                if (!directory) {
                    view.edit(new RemoteEntry(name, path, RemoteEntry.Type.FILE, 0, Instant.now(), "", "", "", "",
                            RemoteEntry.UNKNOWN, true));
                }
            });
        });
        field.setOnAction(event -> create.fire());

        Label hint = note(directory ? "AN EMPTY FOLDER, WITH THE SERVER'S DEFAULT MODE."
                : "AN EMPTY FILE, OPENED IN THE EDITOR RIGHT AFTER.");
        setContent(WIDTH, false, 10, List.of(title(directory ? "NEW FOLDER" : "NEW FILE", "IN " + folder.path(), false),
                field, hint, status, buttons(create, back)));
        focus(field, 0);
    }

    void showDownload(RemoteEntry subject) {
        Path[] folder = {view.downloadFolder()};
        Label where = label(folder[0].toString(), "picker-destination");
        where.setWrapText(true);
        where.setMinHeight(Region.USE_PREF_SIZE);
        Label status = status();
        Button choose = ghost("CHOOSE ANOTHER FOLDER…", () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Download " + displayName(subject) + " to");
            if (Files.isDirectory(folder[0])) {
                chooser.setInitialDirectory(folder[0].toFile());
            }
            File picked = getScene() == null ? null : chooser.showDialog(getScene().getWindow());
            if (picked != null) {
                folder[0] = picked.toPath();
                where.setText(folder[0].toString());
            }
        });
        Button download = primary("DOWNLOAD");
        download.setOnAction(event -> {
            if (!Files.isDirectory(folder[0]) || !Files.isWritable(folder[0])) {
                setStatus(status, "THIS FOLDER CANNOT BE WRITTEN", "card-problem");
                return;
            }
            view.closeCard();
            view.download(subject, folder[0]);
        });
        Button back = ghost("BACK", entry != null ? this::showEntryMenu : this::showFolderMenu);

        Label what = note(subject.isDirectory()
                ? "THE FOLDER AND EVERYTHING INSIDE IT, SENT AS ONE STREAM AND REBUILT HERE. LINKS ARE LEFT OUT."
                : "THIS FILE, " + Format.bytes(subject.sizeBytes()) + ".");
        Label tip = note("TIP: OPEN ⇅ THIS PC AND DRAG ENTRIES OF THE TREE ONTO IT.");
        setContent(WIDTH + 40, false, 10, List.of(title("DOWNLOAD", subject.path(), false), what,
                label("TO THIS COMPUTER", "metric-label"), where, choose, status, buttons(download, back), tip));
        focus(download, -1);
    }

    void showPermissions(RemoteEntry subject) {
        PermissionMode mode = PermissionMode.parseSymbolic(subject.permissions()).orElseThrow();
        String lock = !gated() || account.canChangeMode(subject) ? null
                : "ONLY " + subject.owner().toUpperCase(Locale.ROOT) + " (THE OWNER) OR ROOT CAN CHANGE THIS MODE. "
                + "YOU CAN STILL READ IT HERE.";
        PermissionsPanel panel = new PermissionsPanel(subject, mode, lock);
        panel.setOnBack(entry != null ? this::showEntryMenu : this::showFolderMenu);
        panel.setOnApply(next -> {
            panel.working("APPLYING " + next.octal() + "…");
            view.run(() -> view.browser().changeMode(subject.path(), next), result -> {
                if (result.success()) {
                    view.closeCard();
                    view.changed(subject.path(), RemoteEntry.parentOf(subject.path()).orElse(null));
                    view.notice("MODE " + next.octal() + "  (" + next.symbolic() + ")  SET ON  " + subject.path(), false);
                } else {
                    panel.failed(FileCommands.problem(result));
                }
            });
        });
        setContent(WIDE, false, 0, List.of(panel));
    }

    // Reasons an action is not offered

    /**
     * True while the connected user's own permissions still decide what is offered. Root mode lifts them: the
     * command will run as root, so withholding the action would be telling the user something untrue.
     */
    private boolean gated() {
        return account != null && !view.elevated();
    }

    private String removalProblem(RemoteEntry subject) {
        if (folder == null) {
            return "THE SERVER ROOT CANNOT BE CHANGED";
        }
        if (gated() && !account.canRemove(subject, folder)) {
            return "NEEDS WRITE PERMISSION ON " + displayName(folder).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private String downloadProblem(RemoteEntry subject) {
        if (subject.path().equals("/")) {
            return "THE WHOLE SERVER CANNOT BE DOWNLOADED";
        }
        if (subject.type() == RemoteEntry.Type.OTHER) {
            return "NOT A REGULAR FILE";
        }
        if (gated() && !account.canRead(subject)) {
            return "NO READ PERMISSION";
        }
        return subject.isDirectory() ? enterProblem(subject) : null;
    }

    private String enterProblem(RemoteEntry subject) {
        if (view.elevated()) {
            return null;
        }
        return !subject.readable() || account != null && !account.canExecute(subject)
                ? "NO PERMISSION TO ENTER" : null;
    }

    private String copyProblem(RemoteEntry subject) {
        if (folder == null) {
            return "THE SERVER ROOT CANNOT BE COPIED";
        }
        if (gated() && !account.canRead(subject)) {
            return "NO READ PERMISSION";
        }
        return subject.isDirectory() ? enterProblem(subject) : null;
    }

    private String editProblem(RemoteEntry subject) {
        if (subject.type() == RemoteEntry.Type.OTHER) {
            return "NOT A REGULAR FILE";
        }
        if (gated() && !account.canRead(subject)) {
            return "NO READ PERMISSION";
        }
        if (subject.type() == RemoteEntry.Type.FILE && subject.sizeBytes() > FileCommands.MAX_EDIT_BYTES) {
            return "TOO LARGE TO EDIT HERE (" + Format.bytes(FileCommands.MAX_EDIT_BYTES) + " AT MOST)";
        }
        return null;
    }

    private static String modeProblem(RemoteEntry subject) {
        if (subject.type() == RemoteEntry.Type.LINK) {
            return "A LINK USES THE MODE OF ITS TARGET";
        }
        return PermissionMode.parseSymbolic(subject.permissions()).isEmpty() ? "MODE NOT READ YET" : null;
    }

    private static String deletionScope(RemoteEntry subject) {
        return switch (subject.type()) {
            case DIRECTORY -> subject.childCount() > 0
                    ? "THIS FOLDER AND EVERYTHING INSIDE IT: " + subject.childCount() + " ENTRIES AT ITS FIRST LEVEL, "
                    + "AND ALL THEIR CONTENT."
                    : subject.childCount() == 0 ? "THIS EMPTY FOLDER." : "THIS FOLDER AND EVERYTHING INSIDE IT.";
            case LINK -> "THIS LINK ONLY. WHAT IT POINTS TO (" + subject.linkTarget() + ") STAYS.";
            default -> "THIS FILE, " + Format.bytes(subject.sizeBytes()) + ".";
        };
    }

    /**
     * What the connected user may do with an entry, in words.
     */
    static String accessText(Account account, RemoteEntry subject, boolean elevated) {
        if (account == null) {
            return "";
        }
        if (elevated) {
            return "ROOT MODE IS ON: EVERYTHING IS ALLOWED";
        }
        if (account.isRoot()) {
            return "YOU ARE ROOT: EVERYTHING IS ALLOWED";
        }
        if (PermissionMode.parseSymbolic(subject.permissions()).isEmpty()) {
            return "YOUR RIGHTS: NOT KNOWN YET";
        }
        boolean folder = subject.isDirectory();
        List<String> rights = new ArrayList<>();
        if (account.canRead(subject)) {
            rights.add(folder ? "LIST" : "READ");
        }
        if (account.canWrite(subject)) {
            rights.add(folder ? "CHANGE INSIDE" : "WRITE");
        }
        if (account.canExecute(subject)) {
            rights.add(folder ? "ENTER" : "RUN");
        }
        String as = " AS " + account.classOf(subject).name();
        return rights.isEmpty() ? "YOU HAVE NO RIGHT HERE" + as : "YOU CAN " + String.join(" · ", rights) + as;
    }

    // Building blocks

    private void run(String working, Label status, List<Node> controls, Supplier<CommandEntry> action, Runnable done) {
        setStatus(status, working, "card-note");
        controls.forEach(control -> control.setDisable(true));
        view.run(action, result -> {
            if (result.success()) {
                done.run();
            } else {
                setStatus(status, FileCommands.problem(result), "card-problem");
                controls.forEach(control -> control.setDisable(false));
            }
        });
    }

    private void setContent(double width, boolean danger, double spacing, List<Node> children) {
        getStyleClass().setAll("float-card");
        if (danger) {
            getStyleClass().add("float-card-danger");
        }
        setSpacing(spacing);
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);
        getChildren().setAll(children);
    }

    private VBox header(RemoteEntry subject, String kicker) {
        Circle dot = new Circle(4);
        Color color = BranchStyle.color(subject);
        boolean ring = subject.isDirectory() && !subject.readable() || subject.type() == RemoteEntry.Type.LINK;
        dot.setFill(ring ? Color.TRANSPARENT : color);
        dot.setStroke(subject.isDirectory() && !subject.readable() ? Color.web("#ff2d55") : color);
        Label name = label(displayName(subject), "card-title");
        name.setWrapText(true);
        name.setMaxWidth(WIDTH - 60);
        HBox title = new HBox(9, dot, name);
        title.setAlignment(Pos.CENTER_LEFT);

        Label path = label(subject.path(), "mono-muted");
        path.setWrapText(true);
        Optional<PermissionMode> mode = PermissionMode.parseSymbolic(subject.permissions());
        Label modeLine = label(mode.isEmpty() ? "MODE NOT READ YET"
                : subject.permissions() + "  ·  " + mode.get().octal() + "  ·  " + subject.owner() + " : "
                + subject.group(), "mono");
        VBox header = new VBox(3, label(kicker, "series"), title, path, modeLine);
        String access = accessText(account, subject, view.elevated());
        if (!access.isEmpty()) {
            header.getChildren().add(label(access, "metric-label"));
        }
        VBox.setMargin(header, new Insets(0, 0, 10, 0));
        return header;
    }

    private static Node action(String text, String key, String blocked, boolean danger, Runnable run) {
        if (blocked != null) {
            Label reason = label(blocked, "menu-blocked-reason");
            reason.setWrapText(true);
            VBox box = new VBox(1, label(text, "menu-blocked-name"), reason);
            box.getStyleClass().add("menu-blocked");
            return box;
        }
        Label name = new Label(text);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = label(key, "menu-key");
        HBox graphic = new HBox(name, spacer, hint);
        graphic.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button(null, graphic);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("menu-action");
        if (danger) {
            button.getStyleClass().add("menu-danger");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        graphic.prefWidthProperty().bind(button.widthProperty().subtract(16));
        button.setOnAction(event -> run.run());
        return button;
    }

    private static VBox title(String text, String detail, boolean danger) {
        Label title = label(text, "card-title");
        if (danger) {
            title.getStyleClass().add("card-title-danger");
        }
        Label path = label(detail, "mono-muted");
        path.setWrapText(true);
        return new VBox(3, title, path);
    }

    private static TextField field(String text, String prompt) {
        TextField field = new TextField(text);
        field.setPromptText(prompt);
        field.getStyleClass().add("card-field");
        return field;
    }

    private static Label note(String text) {
        Label note = label(text, "card-note");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        return note;
    }

    private static Label status() {
        Label status = label("", "card-problem");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        return status;
    }

    private static void setStatus(Label status, String text, String styleClass) {
        status.getStyleClass().setAll("label", styleClass);
        status.setText(text);
    }

    private static Button primary(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("primary-button", "compact-button");
        return button;
    }

    private static Button ghost(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox buttons(Node... buttons) {
        HBox row = new HBox(18, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(row, new Insets(4, 0, 0, 0));
        return row;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("card-rule");
        VBox.setMargin(rule, new Insets(4, 0, 4, 0));
        return rule;
    }

    private static void focus(Node node, int caret) {
        Platform.runLater(() -> {
            node.requestFocus();
            if (node instanceof TextField field && caret >= 0) {
                field.selectRange(0, caret);
            }
        });
    }

    static String displayName(RemoteEntry subject) {
        return subject.path().equals("/") ? "/" : subject.name();
    }

    static String typeName(RemoteEntry entry) {
        return switch (entry.type()) {
            case DIRECTORY -> "FOLDER";
            case LINK -> "LINK";
            case OTHER -> "SPECIAL";
            case FILE -> entry.extension().isEmpty() ? "FILE" : entry.extension().toUpperCase(Locale.ROOT) + " FILE";
        };
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
