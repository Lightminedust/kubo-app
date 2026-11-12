package com.kubo.ui.services;

import com.kubo.model.files.PathKind;
import com.kubo.model.services.UnitDraft;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Making something on the server into a service, as a card over the screen.
 *
 * <p>On the left what a person decides: the name, what it runs and where, who it runs as, what happens when it
 * dies, and whether it starts at boot and now. Each path can be typed or walked to. On the right the file that
 * will be written, kept in step with the choices, and editable: what is on the card is what is sent.
 */
final class NewServiceCard extends VBox {

    /** Opens a picker on the server and hands back what was chosen. */
    interface Picker {
        void pick(PathKind kind, String start, Consumer<String> chosen);
    }

    /** Writes the service. The callback is called with null once it is done, or with what went wrong. */
    interface Creator {
        void create(UnitDraft draft, String text, boolean startNow, Consumer<String> done);
    }

    private static final int[] WAITS = {1, 5, 10, 30};
    private static final String[][] POLICIES = {{"no", "stay down"}, {"on-failure", "on failure"},
        {"always", "always"}};

    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final TextField command = new TextField();
    private final TextField arguments = new TextField();
    private final TextField directory = new TextField();
    private final TextField user = new TextField();
    private final TextArea file = new TextArea();
    private final Label fileKey = new Label();
    private final Label problem = new Label();
    private final Label interpreterHint = new Label();
    private final Button interpreter = ServicesScope.action("", null);
    private final Button rebuild = ServicesScope.action("build it from the choices again", null);
    private final Button create = new Button("create it");
    private final List<Button> policyChips = new ArrayList<>();
    private final List<Button> waitChips = new ArrayList<>();
    private final List<Button> userChips = new ArrayList<>();
    private final Button bootChip = chip("start it at every boot");
    private final Button nowChip = chip("start it now");
    private final FlowPane waitRow = new FlowPane(8, 6);
    private String policy = "on-failure";
    private int wait = 5;
    private boolean atBoot = true;
    private boolean startNow = true;
    /** True once the file was edited by hand: the choices no longer write over it. */
    private boolean edited;
    private boolean writing;

    NewServiceCard(String account, Picker picker, Runnable onClose, Creator creator) {
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(16);
        setPadding(new Insets(22, 28, 20, 26));
        setPrefWidth(1040);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label("new service");
        title.getStyleClass().add("fetch-host");
        Label under = new Label("systemd starts it, starts it again when it falls, and after every reboot");
        under.getStyleClass().add("home-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = ServicesScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, under, spacer, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        for (TextField field : List.of(name, description, command, arguments, directory, user)) {
            field.getStyleClass().add("card-field");
            field.textProperty().addListener((observable, before, now) -> update());
        }
        name.setPromptText("backup");
        description.setPromptText("what it is for, in a line");
        command.setPromptText("/opt/backup/run.sh");
        arguments.setPromptText("its arguments, if any");
        directory.setPromptText("/opt/backup, or empty");
        user.setPromptText("other user");
        user.setPrefColumnCount(10);
        command.textProperty().addListener((observable, before, now) -> {
            if (name.getText().isBlank() || name.getText().equals(guessName(before))) {
                name.setText(guessName(now));
            }
        });

        Button browseCommand = ServicesScope.action("browse", event -> {
            if (picker != null) {
                picker.pick(PathKind.PROGRAM, folderOf(command.getText()), chosen -> {
                    command.setText(chosen);
                    if (directory.getText().isBlank()) {
                        directory.setText(folderOf(chosen));
                    }
                });
            }
        });
        Button browseFolder = ServicesScope.action("browse", event -> {
            if (picker != null) {
                picker.pick(PathKind.FOLDER, directory.getText().isBlank() ? folderOf(command.getText())
                        : directory.getText().strip(), directory::setText);
            }
        });
        browseCommand.setDisable(picker == null);
        browseFolder.setDisable(picker == null);

        interpreter.getStyleClass().add("apps-lit");
        interpreter.setOnAction(event -> splitInterpreter());
        interpreterHint.getStyleClass().add("home-muted");
        interpreterHint.setWrapText(true);
        interpreterHint.setMinHeight(Region.USE_PREF_SIZE);
        HBox interpreterRow = new HBox(10, interpreterHint, interpreter);
        interpreterRow.setAlignment(Pos.CENTER_LEFT);
        interpreterRow.managedProperty().bind(interpreterRow.visibleProperty());
        interpreterRow.visibleProperty().bind(interpreter.visibleProperty());

        FlowPane userRow = new FlowPane(8, 6);
        userRow.setAlignment(Pos.CENTER_LEFT);
        userRow.getChildren().add(userChip("root", ""));
        if (account != null && !account.isBlank() && !account.equals("root")) {
            userRow.getChildren().add(userChip(account, account));
        }
        userRow.getChildren().add(user);

        FlowPane policyRow = new FlowPane(8, 6);
        for (String[] choice : POLICIES) {
            Button chip = chip(choice[1]);
            chip.setUserData(choice[0]);
            chip.setOnAction(event -> {
                policy = choice[0];
                update();
            });
            policyChips.add(chip);
            policyRow.getChildren().add(chip);
        }
        Label after = new Label("after");
        after.getStyleClass().add("home-muted");
        waitRow.getChildren().add(after);
        for (int seconds : WAITS) {
            Button chip = chip(seconds + "s");
            chip.setUserData(seconds);
            chip.setOnAction(event -> {
                wait = seconds;
                update();
            });
            waitChips.add(chip);
            waitRow.getChildren().add(chip);
        }
        bootChip.setOnAction(event -> {
            atBoot = !atBoot;
            update();
        });
        nowChip.setOnAction(event -> {
            startNow = !startNow;
            update();
        });

        HBox.setHgrow(command, Priority.ALWAYS);
        HBox commandRow = new HBox(14, command, browseCommand);
        commandRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(directory, Priority.ALWAYS);
        HBox folderRow = new HBox(14, directory, browseFolder);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        GridPane form = new GridPane();
        form.setHgap(18);
        form.setVgap(12);
        form.addRow(0, key("name"), new VBox(4, name, hint(".service is added to it")));
        form.addRow(1, key("for"), description);
        form.addRow(2, key("runs"), new VBox(6, commandRow, interpreterRow));
        form.addRow(3, key("with"), arguments);
        form.addRow(4, key("in"), folderRow);
        form.addRow(5, key("as"), new VBox(4, userRow, hint("root when nothing is chosen")));
        form.addRow(6, key("dies"), new VBox(6, policyRow, waitRow));
        form.addRow(7, key("starts"), new HBox(8, bootChip, nowChip));
        form.getChildren().forEach(node -> {
            if (GridPane.getColumnIndex(node) == 0) {
                GridPane.setValignment(node, VPos.TOP);
            }
            GridPane.setHgrow(node, GridPane.getColumnIndex(node) == 1 ? Priority.ALWAYS : Priority.NEVER);
        });
        form.setPrefWidth(500);
        form.setMinWidth(440);

        fileKey.getStyleClass().add("fetch-key");
        file.getStyleClass().add("redis-diagnostic-output");
        file.setWrapText(false);
        file.setPrefRowCount(19);
        file.setPrefColumnCount(46);
        file.setOnKeyTyped(event -> {
            if (!writing) {
                edited = true;
                rebuild.setVisible(true);
                update();
            }
        });
        rebuild.setVisible(false);
        rebuild.managedProperty().bind(rebuild.visibleProperty());
        rebuild.setOnAction(event -> {
            edited = false;
            rebuild.setVisible(false);
            update();
        });
        VBox.setVgrow(file, Priority.ALWAYS);
        Label sent = hint("this text is what is written, as it is");
        VBox right = new VBox(8, fileKey, file, new HBox(14, sent, rebuild));
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox body = new HBox(28, form, right);

        problem.getStyleClass().add("card-problem");
        problem.setWrapText(true);
        problem.setMinHeight(Region.USE_PREF_SIZE);
        problem.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(problem, Priority.ALWAYS);
        create.getStyleClass().addAll("primary-button", "compact-button");
        create.setMinWidth(Region.USE_PREF_SIZE);
        create.setOnAction(event -> {
            UnitDraft draft = draft();
            if (!draft.ready() || creator == null) {
                return;
            }
            create.setDisable(true);
            create.setText("writing…");
            creator.create(draft, file.getText(), startNow, trouble -> {
                create.setText("create it");
                create.setDisable(false);
                if (trouble != null) {
                    problem.setText(trouble);
                }
            });
        });
        Button cancel = ServicesScope.action("cancel", event -> onClose.run());
        HBox buttons = new HBox(16, problem, cancel, create);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        getChildren().addAll(top, body, buttons);
        update();
    }

    /** Only for the previews: the card as somebody filled it. */
    void fillForPreview(String path, String folder) {
        command.setText(path);
        directory.setText(folder);
        description.setText("nightly copy of the database to the backup disk");
        update();
    }

    private UnitDraft draft() {
        return new UnitDraft(name.getText(), description.getText(), command.getText(), arguments.getText(),
                directory.getText(), user.getText(), policy, wait, atBoot);
    }

    /** Everything that follows from the choices: the chips lit, the file written, what is still missing. */
    private void update() {
        UnitDraft draft = draft();
        for (Button chip : policyChips) {
            light(chip, chip.getUserData().equals(policy));
        }
        for (Button chip : waitChips) {
            light(chip, chip.getUserData().equals(wait));
        }
        for (Button chip : userChips) {
            light(chip, chip.getUserData().equals(user.getText().strip()));
        }
        waitRow.setVisible(!policy.equals("no"));
        waitRow.setManaged(!policy.equals("no"));
        light(bootChip, atBoot);
        light(nowChip, startNow);
        fileKey.setText("writes /etc/systemd/system/" + (draft.name().isEmpty() ? "…" : draft.unitName()));
        if (!edited) {
            writing = true;
            file.setText(draft.text());
            writing = false;
        }
        String[] guess = interpreterFor(command.getText().strip());
        interpreter.setVisible(guess != null);
        if (guess != null) {
            interpreter.setText("run it with " + guess[0]);
            interpreterHint.setText("a script runs on its own only with its #! line and the x right.");
        }
        List<String> problems = draft.problems();
        problem.setText(problems.isEmpty() ? "" : problems.get(0));
        create.setDisable(!problems.isEmpty());
    }

    /**
     * Puts the program a script needs in front of it: the script moves to its arguments. Kubo cannot know
     * where Node or Python is on this server, so the usual place is offered and shown, never hidden.
     */
    private void splitInterpreter() {
        String script = command.getText().strip();
        String[] guess = interpreterFor(script);
        if (guess == null) {
            return;
        }
        String rest = arguments.getText().strip();
        arguments.setText(script + (rest.isEmpty() ? "" : " " + rest));
        command.setText(guess[1]);
    }

    /** The program a script of this ending is run with, and where it usually is; null for anything else. */
    private static String[] interpreterFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sh")) {
            return new String[]{"bash", "/bin/bash"};
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return new String[]{"node", "/usr/bin/node"};
        }
        if (lower.endsWith(".py")) {
            return new String[]{"python3", "/usr/bin/python3"};
        }
        if (lower.endsWith(".php")) {
            return new String[]{"php", "/usr/bin/php"};
        }
        if (lower.endsWith(".rb")) {
            return new String[]{"ruby", "/usr/bin/ruby"};
        }
        return null;
    }

    /** A name from what it runs: run.sh in /opt/backup is "backup", server.js is "server". */
    private static String guessName(String path) {
        String trimmed = path == null ? "" : path.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("/");
        String last = parts[parts.length - 1];
        int dot = last.lastIndexOf('.');
        String base = dot > 0 ? last.substring(0, dot) : last;
        if ((base.equals("run") || base.equals("start") || base.equals("main") || base.equals("index"))
                && parts.length >= 2 && !parts[parts.length - 2].isEmpty()) {
            base = parts[parts.length - 2];
        }
        String cleaned = base.replaceAll("[^A-Za-z0-9_.@-]", "-");
        return UnitDraft.validName(cleaned) ? cleaned : "";
    }

    private static String folderOf(String path) {
        String trimmed = path == null ? "" : path.strip();
        int slash = trimmed.lastIndexOf('/');
        return trimmed.startsWith("/") && slash > 0 ? trimmed.substring(0, slash) : null;
    }

    private Button userChip(String text, String value) {
        Button chip = chip(text);
        chip.setUserData(value);
        chip.setOnAction(event -> user.setText(value));
        userChips.add(chip);
        return chip;
    }

    private static void light(Button chip, boolean on) {
        chip.getStyleClass().remove("service-chip-active");
        if (on) {
            chip.getStyleClass().add("service-chip-active");
        }
    }

    private static Button chip(String text) {
        Button chip = new Button(text);
        chip.getStyleClass().add("service-chip");
        chip.setFocusTraversable(false);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    private static Label hint(String text) {
        Label hint = new Label(text);
        hint.getStyleClass().add("home-muted");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        return hint;
    }

    private static Label key(String text) {
        Label key = new Label(text);
        key.getStyleClass().add("fetch-key");
        key.setMinWidth(54);
        key.setPadding(new Insets(5, 0, 0, 0));
        return key;
    }
}
