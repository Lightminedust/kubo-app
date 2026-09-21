package com.kubo.ui.services;

import com.kubo.files.FileBrowser;
import com.kubo.files.FileCommands;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.services.UnitDraft;
import com.kubo.ui.PromptCard;
import java.util.Optional;
import com.kubo.ui.files.BranchCanvas;
import com.kubo.ui.files.EditorWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Making a service out of something already on the server.
 *
 * <p>It is the file tree that answers the hard part. A person knows where their script is far better than
 * they know how to spell it, so they walk to it the way they walk to any other file in Kubo, and the screen
 * turns what they clicked into the two lines that matter: what to run, and where to run it.
 *
 * <p>The tree is the whole file tree, not a picture of one. Right click and there is the folder to make, the
 * file to create, the editor to write it in — because somebody making a service out of a script may not have
 * written the script yet, and sending them to another screen for it would be absurd.
 *
 * <p>What will be written is on the screen while it is being decided, and it is the same string that is sent.
 * Nothing is generated a second time on the way out.
 */
final class ServiceBuilder extends BorderPane {

    /** What the screen above does with a finished service: write it, reload, and perhaps start it. */
    interface Hands {

        /**
         * @param draft what the fields say, for the name and what to do once it is written
         * @param text  the file itself, which may have been edited by hand and is what gets written
         * @param done  called with null when it worked, or the reason it did not
         */
        void create(UnitDraft draft, String text, Consumer<String> done);
    }

    private final FileBrowser browser;
    private final ExecutorService worker;
    private final Hands hands;
    private final Consumer<Region> showOverlay;
    private final Runnable hideOverlay;
    private final BranchCanvas tree = new BranchCanvas();

    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final TextField command = new TextField();
    private final TextField directory = new TextField();
    private final TextField user = new TextField();
    private final TextField delay = new TextField("5");
    private final CheckBox atBoot = new CheckBox("START IT AT EVERY BOOT");
    private final CheckBox startNow = new CheckBox("START IT NOW");
    private final TextArea preview = new TextArea();
    private final Label problems = label("", "card-problem");
    private final Button create = new Button("CREATE IT");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final HBox policyRow = new HBox(8);

    private final Label edited = label("", "card-note");
    /** Sits under WHAT IT RUNS, and only says anything when the file picked cannot be run. */
    private final VBox runnable = new VBox(6);
    private String policy = "on-failure";
    private String here = "/";
    private ContextMenu menu;
    /** True once the file has been typed in by hand, after which the fields stop rewriting it. */
    private boolean byHand;
    /** True while the preview is being rewritten from the fields, so that does not count as a hand edit. */
    private boolean rewriting;
    /** The file the tree last put in WHAT IT RUNS, and its rights as that listing gave them. */
    private RemoteEntry picked;

    ServiceBuilder(FileBrowser browser, ExecutorService worker, Hands hands, Runnable onCancel,
                   Consumer<Region> showOverlay, Runnable hideOverlay) {
        this.browser = browser;
        this.worker = worker;
        this.hands = hands;
        this.showOverlay = showOverlay;
        this.hideOverlay = hideOverlay;
        getStyleClass().add("scope-view");

        // One line at the top, one at the bottom, and everything else between them. The screen this sits in
        // has a masthead of its own already; a second one pushed the buttons off the bottom of the window.
        setTop(buildHead(onCancel));
        setLeft(buildForm());
        setCenter(buildTree());
        setBottom(buildFoot(onCancel));
        redraw();
        open("/");
    }

    // The parts

    private HBox buildHead(Runnable onCancel) {
        Button back = new Button("←  SERVICES");
        back.getStyleClass().add("rail-button");
        back.setFocusTraversable(false);
        back.setOnAction(event -> onCancel.run());
        Label kicker = label("NEW SERVICE", "series");
        Label lede = label("WALK THE TREE TO WHAT IT SHOULD RUN  ·  RIGHT CLICK TO MAKE, EDIT, "
                + "RENAME OR DELETE A FILE", "metric-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // The one screen here that writes something new rather than reading something already there,
        // so it gets a card of its own rather than the services one.
        Button help = new Button("?");
        help.getStyleClass().add("rail-button");
        help.setFocusTraversable(false);
        help.setOnAction(event -> showOverlay.accept(new BuilderHelp(hideOverlay)));
        HBox head = new HBox(16, back, kicker, spacer, lede, help);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(10, 26, 10, 24));
        head.getStyleClass().add("sheet-masthead");
        return head;
    }

    private ScrollPane buildForm() {
        name.setPromptText("backup");
        description.setPromptText("what it is for, in a line");
        command.setPromptText("/opt/backup/run.sh");
        directory.setPromptText("/opt/backup");
        user.setPromptText("root, or a user of your own");
        delay.setPrefColumnCount(4);
        for (TextField field : List.of(name, description, command, directory, user, delay)) {
            field.getStyleClass().add("card-field");
            field.textProperty().addListener((observable, before, now) -> redraw());
        }
        command.textProperty().addListener((observable, before, now) -> sayIfItCannotRun());
        sayIfItCannotRun();
        atBoot.setSelected(true);
        startNow.setSelected(true);
        atBoot.selectedProperty().addListener((observable, before, now) -> redraw());

        policyRow.setAlignment(Pos.CENTER_LEFT);
        String[][] policies = {{"no", "NEVER"}, {"on-failure", "ON FAILURE"}, {"always", "ALWAYS"}};
        for (String[] option : policies) {
            Button button = new Button(option[1]);
            button.getStyleClass().addAll("rail-button", "compact-button");
            button.setFocusTraversable(false);
            button.setOnAction(event -> {
                policy = option[0];
                redraw();
            });
            policyRow.getChildren().add(button);
        }

        // Editable, because a unit file has thirty settings Kubo does not offer and somebody will want one
        // of them. Once it is touched, the fields stop rewriting it: whatever is in this box is the file.
        preview.setEditable(true);
        preview.setWrapText(false);
        preview.getStyleClass().add("redis-diagnostic-output");
        preview.setPrefSize(320, 200);
        preview.textProperty().addListener((observable, before, now) -> {
            if (!rewriting && !byHand) {
                byHand = true;
                showEdited();
            }
        });
        edited.setWrapText(true);
        Button back = new Button("BUILD IT FROM THE FIELDS AGAIN");
        back.getStyleClass().addAll("ghost-button", "compact-button");
        back.setFocusTraversable(false);
        back.setOnAction(event -> {
            byHand = false;
            edited.setText("");
            redraw();
        });
        VBox editedBox = new VBox(6, edited, back);

        VBox form = new VBox(10,
                label("WHAT TO CALL IT", "series"), name,
                label("WHAT IT IS FOR", "series"), description,
                label("WHAT IT RUNS", "series"), command, runnable,

                label("WHERE IT RUNS", "series"), directory,
                label("WHO IT RUNS AS", "series"), user,
                label("WHEN IT DIES, RESTART IT", "series"), policyRow,
                new HBox(8, label("WAIT", "metric-label"), delay, label("SECONDS FIRST", "metric-label")),
                atBoot, startNow,
                label("THE FILE THIS WILL WRITE", "series"), preview, editedBox);
        form.setPadding(new Insets(16, 20, 16, 24));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefWidth(372);
        scroll.setMinWidth(340);
        return scroll;
    }

    private BorderPane buildTree() {
        Button asCommand = new Button("USE AS WHAT IT RUNS");
        Button asDirectory = new Button("USE AS WHERE IT RUNS");
        Button newFile = new Button("+ FILE");
        Button newFolder = new Button("+ FOLDER");
        for (Button button : List.of(asCommand, asDirectory, newFile, newFolder)) {
            button.getStyleClass().addAll("rail-button", "compact-button");
            button.setFocusTraversable(false);
        }
        asCommand.setOnAction(event -> chosen(true));
        asDirectory.setOnAction(event -> chosen(false));
        newFile.setOnAction(event -> askName(true, here));
        newFolder.setOnAction(event -> askName(false, here));
        Button up = new Button("← BACK");
        up.getStyleClass().add("ghost-button");
        up.setFocusTraversable(false);
        up.setOnAction(event -> tree.back());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rail = new HBox(8, up, newFolder, newFile, spacer, asDirectory, asCommand);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setPadding(new Insets(8, 24, 8, 20));

        tree.setHome("/");
        tree.setFilter(true, false);
        tree.setOnNavigate(entry -> open(entry.path()));
        tree.setOnOpen(entry -> {
            if (entry.isDirectory()) {
                open(entry.path());
            } else {
                command.setText(entry.path());
            }
        });
        tree.setOnMenu(this::showMenu);

        BorderPane box = new BorderPane(tree);
        box.setTop(rail);
        return box;
    }

    private HBox buildFoot(Runnable onCancel) {
        problems.setWrapText(true);
        problems.setMinHeight(Region.USE_PREF_SIZE);
        problems.setMaxWidth(700);

        spinner.getStyleClass().add("connection-progress");
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        spinner.setManaged(false);

        create.getStyleClass().addAll("primary-button", "compact-button");
        create.setOnAction(event -> send());
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> onCancel.run());

        HBox foot = new HBox(12, create, cancel, spinner, problems);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.setPadding(new Insets(10, 26, 12, 24));
        foot.getStyleClass().add("sheet-masthead");
        return foot;
    }

    // The tree

    /** What can be done where the pointer is: pick it, or make something new there. */
    private void showMenu(BranchCanvas.MenuRequest request) {
        if (menu != null) {
            menu.hide();
        }
        RemoteEntry entry = request.subject();
        String folder = entry != null && entry.isDirectory() ? entry.path()
                : request.folder() != null ? request.folder().path() : here;
        List<MenuItem> items = new ArrayList<>();
        if (entry != null && !entry.isDirectory()) {
            items.add(item("USE  " + entry.name() + "  AS WHAT IT RUNS", event -> take(entry)));
            items.add(item("EDIT  " + entry.name(), event -> edit(entry.path())));
            items.add(new SeparatorMenuItem());
            items.add(item("RENAME  " + entry.name(), event -> askRename(entry)));
            items.add(danger("DELETE  " + entry.name(), event -> askDelete(entry)));
            items.add(new SeparatorMenuItem());
        } else if (entry != null) {
            items.add(item("USE  " + entry.name() + "  AS WHERE IT RUNS",
                    event -> directory.setText(entry.path())));
            items.add(new SeparatorMenuItem());
            items.add(item("RENAME  " + entry.name(), event -> askRename(entry)));
            items.add(danger("DELETE  " + entry.name(), event -> askDelete(entry)));
            items.add(new SeparatorMenuItem());
        }
        items.add(item("NEW FILE IN  " + folder, event -> askName(true, folder)));
        items.add(item("NEW FOLDER IN  " + folder, event -> askName(false, folder)));
        menu = new ContextMenu(items.toArray(new MenuItem[0]));
        menu.getStyleClass().add("redis-context-menu");
        // The tree gives the point in its own coordinates; a menu is placed in the screen's. Without the
        // conversion it opened wherever that number happened to land on the desktop.
        javafx.geometry.Point2D at = tree.localToScreen(request.x(), request.y());
        if (at == null) {
            menu.show(tree, Side.TOP, request.x(), request.y());
        } else {
            menu.show(tree, at.getX(), at.getY());
        }
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem entry = new MenuItem(text);
        entry.setOnAction(action);
        return entry;
    }

    /** The same, marked as something that takes away, the way it is marked everywhere else in Kubo. */
    private static MenuItem danger(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem entry = item(text, action);
        entry.getStyleClass().add("redis-menu-danger");
        return entry;
    }

    /**
     * Gives a file the right to be run, for whoever can already read it.
     *
     * <p>A new file is written 0644, which is right for a configuration file and useless for a script: a
     * unit whose ExecStart points at one starts and fails with "Permission denied" every time. The mode is
     * shown in the menu, both before and after, rather than the button meaning something the user has to
     * know already.
     */
    private void makeExecutable(RemoteEntry entry, PermissionMode mode) {
        String folder = parentOf(entry.path());
        PermissionMode wanted = mode.runnable();
        worker.execute(() -> {
            var result = browser.changeMode(entry.path(), wanted);
            Platform.runLater(() -> {
                if (!result.success()) {
                    problems.setText(refused(result));
                    return;
                }
                problems.setText("");
                if (picked != null && picked.path().equals(entry.path())) {
                    // Held rather than re-read: the row has to go now, and the tree is asked for the folder
                    // anyway, which is what will confirm it.
                    picked = withRights(picked, wanted);
                    sayIfItCannotRun();
                }
                tree.refresh(folder, entry.name());
            });
        });
    }

    /** The same entry with the rights it has just been given. */
    private static RemoteEntry withRights(RemoteEntry entry, PermissionMode mode) {
        return new RemoteEntry(entry.name(), entry.path(), entry.type(), entry.sizeBytes(), entry.modified(),
                mode.symbolic(), entry.owner(), entry.group(), entry.linkTarget(), entry.childCount(),
                entry.readable());
    }

    /**
     * Renames a file or a folder in place, and follows it in the fields that were pointing at it.
     *
     * <p>Leaving WHAT IT RUNS on the old path after a rename would build a unit that cannot start, and the
     * screen would have been the one that broke it.
     */
    private void askRename(RemoteEntry entry) {
        String folder = parentOf(entry.path());
        showOverlay.accept(new PromptCard("FILES", "RENAME  " + entry.name(),
                "It stays in " + folder + ".", entry.name(), "RENAME IT", false,
                (typed, done) -> {
                    String wanted = typed.strip();
                    Optional<String> problem = FileCommands.nameProblem(wanted);
                    if (problem.isPresent()) {
                        done.accept(problem.get());
                        return;
                    }
                    if (wanted.equals(entry.name())) {
                        done.accept("That is the name it already has.");
                        return;
                    }
                    String target = RemoteEntry.join(folder, wanted);
                    worker.execute(() -> {
                        var result = browser.move(entry.path(), target);
                        Platform.runLater(() -> {
                            if (!result.success()) {
                                done.accept(refused(result));
                                return;
                            }
                            hideOverlay.run();
                            follow(entry.path(), target);
                            // The old path is not in the tree any more; the new one is what gets selected.
                            tree.forget(entry.path());
                            tree.refresh(folder, wanted);
                        });
                    });
                }, hideOverlay));
    }

    /**
     * Deletes a file or a folder, asking first.
     *
     * <p>A folder with something in it asks for its name to be typed back and everything else asks only for
     * the button, which is the rule the file screen already follows: the weight of the question matches the
     * weight of what it does.
     */
    private void askDelete(RemoteEntry entry) {
        String folder = parentOf(entry.path());
        String what = entry.isDirectory()
                ? "This deletes " + entry.path() + " and everything inside it."
                : "This deletes " + entry.path() + ".";
        String warning = what + " A server has no trash: nothing undoes this.";
        if (entry.isDirectory() && entry.childCount() != 0) {
            showOverlay.accept(new PromptCard("FILES", "DELETE  " + entry.name(),
                    warning + " Type the name of the folder to confirm.", entry.name(), "DELETE IT", true,
                    (typed, done) -> {
                        if (!typed.strip().equals(entry.name())) {
                            done.accept("Type " + entry.name() + " exactly to confirm.");
                            return;
                        }
                        erase(entry, folder, done);
                    }, hideOverlay));
            return;
        }
        showOverlay.accept(PromptCard.confirm("FILES", "DELETE  " + entry.name(), warning, "DELETE IT", true,
                done -> erase(entry, folder, done), hideOverlay));
    }

    private void erase(RemoteEntry entry, String folder, Consumer<String> done) {
        worker.execute(() -> {
            var result = browser.delete(entry);
            Platform.runLater(() -> {
                if (!result.success()) {
                    done.accept(refused(result));
                    return;
                }
                hideOverlay.run();
                follow(entry.path(), null);
                // Nothing is selected afterwards: what was selected is what has just gone.
                tree.forget(entry.path());
                tree.refresh(folder, null);
            });
        });
    }

    /**
     * Keeps the two fields that hold a path pointing at the same thing after it moves, or clears them when
     * it is gone.
     */
    private void follow(String was, String now) {
        if (picked != null && picked.path().startsWith(was)) {
            // What the row knows about is no longer at that path, whatever the fields now say.
            picked = null;
        }
        for (TextField field : List.of(command, directory)) {
            String text = field.getText();
            if (text == null || !text.startsWith(was)) {
                continue;
            }
            // The path itself, or anything under it when a whole folder moved.
            String tail = text.substring(was.length());
            if (!tail.isEmpty() && !tail.startsWith("/")) {
                continue;
            }
            field.setText(now == null ? "" : now + tail);
        }
    }

    /**
     * Asks what to call the new thing, and refuses a name that is a path rather than a name.
     *
     * <p>A name with a slash in it would land somewhere other than the folder the user is looking at, which
     * is not what anybody means by "new file here".
     */
    private void askName(boolean file, String folder) {
        String what = file ? "FILE" : "FOLDER";
        showOverlay.accept(new PromptCard("FILES", "NEW " + what,
                "It will be made in " + folder + ".", file ? "run.sh" : "scripts", "CREATE IT", false,
                (typed, done) -> {
                    String wanted = typed.strip();
                    if (wanted.contains("/") || wanted.equals(".") || wanted.equals("..")) {
                        done.accept("A name, not a path: no slashes.");
                        return;
                    }
                    String path = (folder.endsWith("/") ? folder : folder + "/") + wanted;
                    worker.execute(() -> {
                        var result = file ? browser.createFile(path) : browser.createFolder(path);
                        Platform.runLater(() -> {
                            if (!result.success()) {
                                done.accept(refused(result));
                                return;
                            }
                            hideOverlay.run();
                            // Not open(): the tree drops a listing for a column it did not ask for, so the
                            // new file never appeared. refresh() marks the column, asks for it again, and
                            // puts the selection on what was just made.
                            tree.refresh(folder, wanted);
                            if (file) {
                                // Made, and then opened: the point of making it was to write in it.
                                command.setText(path);
                                edit(path);
                            } else {
                                directory.setText(path);
                            }
                        });
                    });
                }, hideOverlay));
    }

    /** The file editor of the file screen, in a window of its own, on this very file. */
    private void edit(String path) {
        if (browser == null) {
            return;
        }
        new EditorWindow(browser, path, saved -> tree.refresh(parentOf(path), nameOf(path))).show();
    }

    private static String firstLine(String text) {
        return text == null || text.isBlank() ? "It did not work." : text.strip().lines().findFirst().orElse("");
    }

    /**
     * Why it did not work, in words that say what to do about it.
     *
     * <p>"Permission denied" from a shell is true and useless: most of /etc and /opt belong to root, and the
     * thing to do is turn root on, not to read the sentence again.
     */
    private String refused(com.kubo.command.CommandEntry result) {
        String said = firstLine(result.error().isBlank() ? result.output() : result.error());
        boolean denied = said.toLowerCase(java.util.Locale.ROOT).contains("permission denied");
        if (denied && browser != null && !browser.elevated()) {
            return said + "  —  that folder belongs to root. Turn on ROOT in the header and try again.";
        }
        return said;
    }

    // Doing

    /** Puts whatever is selected in the tree into one of the two fields that take a path. */
    private void chosen(boolean asCommand) {
        RemoteEntry entry = tree.selectedEntry();
        if (entry == null) {
            problems.setText("Click something in the tree first.");
            return;
        }
        if (asCommand) {
            take(entry);
        } else {
            directory.setText(entry.isDirectory() ? entry.path() : parentOf(entry.path()));
        }
    }

    /**
     * Puts a file in WHAT IT RUNS and keeps hold of what the listing said about it.
     *
     * <p>Its rights came with the listing, so whether it can be run is already known here: no second
     * question to the server, and no guessing about a path somebody typed.
     */
    void take(RemoteEntry entry) {
        picked = entry;
        command.setText(entry.path());
        sayIfItCannotRun();
    }

    /**
     * Says so, under the field, when the file picked cannot be run.
     *
     * <p>A new file is written 0644, which is right for a configuration file and useless for a script: the
     * service is written, systemd accepts it, and it fails at every start with "Permission denied". That is
     * a bad half hour, and the screen knows the answer before the unit is even written.
     *
     * <p>Nothing is shown for a path typed by hand, because nothing is known about it. Saying "this may not
     * be executable" about every path would be noise in front of the one case that matters.
     */
    private void sayIfItCannotRun() {
        runnable.getChildren().clear();
        // Unmanaged while empty, or it would hold a row of space open under the field for the whole life of
        // the screen, which is worse than the warning it is there to carry.
        runnable.setVisible(false);
        runnable.setManaged(false);
        if (picked == null || !picked.path().equals(command.getText())) {
            return;
        }
        Optional<PermissionMode> mode = PermissionMode.parseSymbolic(picked.permissions());
        if (mode.isEmpty() || mode.get().executable()) {
            return;
        }
        PermissionMode now = mode.get();
        Label says = label("THIS FILE IS NOT EXECUTABLE (" + now.octal() + "). SYSTEMD WOULD FAIL TO START "
                + "THE SERVICE WITH \"PERMISSION DENIED\" EVERY TIME.", "card-problem");
        says.setWrapText(true);
        says.setMaxWidth(320);
        Button fix = new Button("MAKE IT EXECUTABLE  (" + now.octal() + "  \u2192  "
                + now.runnable().octal() + ")");
        fix.getStyleClass().addAll("rail-button", "compact-button");
        fix.setFocusTraversable(false);
        fix.setOnAction(event -> makeExecutable(picked, now));
        runnable.getChildren().setAll(says, fix);
        runnable.setVisible(true);
        runnable.setManaged(true);
    }

    private static String nameOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }

    private static String parentOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut <= 0 ? "/" : path.substring(0, cut);
    }

    private void open(String path) {
        if (browser == null) {
            return;
        }
        here = path;
        worker.execute(() -> {
            DirectoryListing listing = browser.list(path);
            Platform.runLater(() -> tree.setListing(listing));
        });
    }

    /** The draft as the fields say it is, which is also what the preview shows. */
    private UnitDraft draft() {
        int seconds;
        try {
            seconds = Integer.parseInt(delay.getText().trim());
        } catch (NumberFormatException e) {
            seconds = 5;
        }
        return new UnitDraft(name.getText(), description.getText(), command.getText(), directory.getText(),
                user.getText(), policy, seconds, atBoot.isSelected());
    }

    private void showEdited() {
        edited.setText("EDITED BY HAND. WHAT IS IN THE BOX IS WHAT WILL BE WRITTEN; THE FIELDS ABOVE NO "
                + "LONGER REWRITE IT.");
    }

    private void redraw() {
        UnitDraft draft = draft();
        if (!byHand) {
            rewriting = true;
            preview.setText(draft.text());
            rewriting = false;
        }
        List<String> found = draft.problems();
        problems.setText(found.isEmpty() ? "" : String.join("  ", found));
        create.setDisable(!found.isEmpty());
        for (int i = 0; i < policyRow.getChildren().size(); i++) {
            Button button = (Button) policyRow.getChildren().get(i);
            boolean on = UnitDraft.POLICIES[i].equals(policy);
            button.getStyleClass().removeAll("primary-button", "rail-button");
            button.getStyleClass().add(on ? "primary-button" : "rail-button");
        }
    }

    private void send() {
        UnitDraft draft = draft();
        if (!draft.ready()) {
            return;
        }
        create.setDisable(true);
        create.setText("WORKING…");
        spinner.setVisible(true);
        spinner.setManaged(true);
        hands.create(draft, preview.getText(), trouble -> {
            spinner.setVisible(false);
            spinner.setManaged(false);
            create.setText("CREATE IT");
            create.setDisable(false);
            if (trouble != null) {
                problems.setText(trouble);
            }
        });
    }

    /** Whether the new service should be started as soon as it is written. */
    boolean startsNow() {
        return startNow.isSelected();
    }

    void stop() {
        if (menu != null) {
            menu.hide();
        }
        tree.stop();
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
