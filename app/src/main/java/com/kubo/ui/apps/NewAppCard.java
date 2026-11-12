package com.kubo.ui.apps;

import com.kubo.apps.AppCommands;
import com.kubo.model.files.PathKind;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Starting a new application under PM2: what it runs, its name, how many workers, the port it is given, the
 * memory it may take, and whether it comes back after a reboot.
 *
 * <p>The path is typed or walked to, whichever the reader likes better. The command it will send is written
 * under the form as it is filled, exactly as it goes to the server.
 */
final class NewAppCard extends VBox {

    /** Opens a picker on the server and hands back what was chosen. */
    interface Picker {
        void pick(PathKind kind, String start, Consumer<String> chosen);
    }

    private static final int[] LIMITS = {0, 256, 512, 1024, 2048};

    private final TextField path = new TextField();
    private final TextField name = new TextField();
    private final TextField port = new TextField();
    private final Label command = new Label();
    private final Label problem = new Label();
    private final Label kindHint = hint("");
    private final Label workerHint = hint("");
    private final Label limitHint = hint("");
    private final Button start = new Button("start it");
    private final List<Button> kinds = new ArrayList<>();
    private final List<Button> workerChoices = new ArrayList<>();
    private final List<Button> limitChoices = new ArrayList<>();
    private final Button saveChoice = chip("comes back after a reboot");
    private final int cores;
    private final String home;
    private boolean npm;
    private int workers = 1;
    private int limit;
    private boolean save = true;
    private boolean nameTouched;

    /**
     * @param cores  the machine's cores, to offer as many workers as it has and no more
     * @param home   the account's home, where browsing starts when nothing is typed yet
     * @param picker opens the server's folders, or null when there is no server to walk
     */
    NewAppCard(int cores, String home, Picker picker, Runnable onClose, Consumer<AppCommands.NewApp> onStart) {
        this.cores = cores;
        this.home = home == null ? "" : home;
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(16);
        setPadding(new Insets(22, 28, 20, 26));
        setPrefWidth(620);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label("new app");
        title.getStyleClass().add("fetch-host");
        Label under = new Label("pm2 starts it, and starts it again when it falls");
        under.getStyleClass().add("home-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = AppsScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, under, spacer, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        Button script = chip("a script");
        Button npmStart = chip("npm start");
        kinds.add(script);
        kinds.add(npmStart);
        script.setOnAction(event -> {
            npm = false;
            update();
        });
        npmStart.setOnAction(event -> {
            npm = true;
            workers = 1;
            update();
        });

        FlowPane workerRow = new FlowPane(8, 6);
        int most = Math.max(1, cores);
        for (int count : new int[]{1, 2, 4, 8, 16}) {
            if (count == 1 || count <= most) {
                workerRow.getChildren().add(workerChip(String.valueOf(count), count));
            }
        }
        if (most > 1) {
            workerRow.getChildren().add(workerChip("one per core" + (cores > 0 ? " (" + cores + ")" : ""), 0));
        }
        FlowPane limitRow = new FlowPane(8, 6);
        for (int megabytes : LIMITS) {
            Button chip = chip(megabytes == 0 ? "no limit" : megabytes >= 1024 ? megabytes / 1024 + " GB"
                    : megabytes + " MB");
            chip.setUserData(megabytes);
            chip.setOnAction(event -> {
                limit = megabytes;
                update();
            });
            limitChoices.add(chip);
            limitRow.getChildren().add(chip);
        }
        saveChoice.setOnAction(event -> {
            save = !save;
            update();
        });

        path.getStyleClass().add("card-field");
        name.getStyleClass().add("card-field");
        port.getStyleClass().add("card-field");
        port.setPromptText("3000, or empty");
        port.setPrefColumnCount(10);
        port.setMaxWidth(150);
        path.textProperty().addListener((observable, before, now) -> {
            if (!nameTouched) {
                name.setText(guessName(now));
            }
            update();
        });
        name.textProperty().addListener((observable, before, now) -> update());
        name.setOnKeyTyped(event -> nameTouched = true);
        port.textProperty().addListener((observable, before, now) -> update());

        Button browse = AppsScope.action("browse", event -> {
            if (picker != null) {
                picker.pick(npm ? PathKind.FOLDER : PathKind.SCRIPT, startFolder(), chosen -> {
                    path.setText(chosen);
                    path.positionCaret(chosen.length());
                });
            }
        });
        browse.setDisable(picker == null);
        HBox.setHgrow(path, Priority.ALWAYS);
        HBox pathRow = new HBox(14, path, browse);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        Label portHint = hint("handed to it as PORT");
        HBox portRow = new HBox(14, port, portHint);
        portRow.setAlignment(Pos.CENTER_LEFT);

        GridPane form = new GridPane();
        form.setHgap(18);
        form.setVgap(13);
        form.addRow(0, key("runs"), new VBox(6, new HBox(8, script, npmStart), kindHint));
        form.addRow(1, key("path"), pathRow);
        form.addRow(2, key("name"), name);
        form.addRow(3, key("workers"), new VBox(6, workerRow, workerHint));
        form.addRow(4, key("port"), portRow);
        form.addRow(5, key("memory"), new VBox(6, limitRow, limitHint));
        form.addRow(6, key("reboot"), saveChoice);
        GridPane.setHgrow(pathRow, Priority.ALWAYS);
        GridPane.setHgrow(name, Priority.ALWAYS);
        form.getChildren().forEach(node -> {
            if (GridPane.getColumnIndex(node) == 0) {
                GridPane.setValignment(node, javafx.geometry.VPos.TOP);
            }
        });

        Label sends = new Label("sends");
        sends.getStyleClass().add("fetch-key");
        command.getStyleClass().add("fetch-value");
        command.setWrapText(true);
        command.setMinHeight(Region.USE_PREF_SIZE);
        VBox sent = new VBox(command);
        sent.getStyleClass().add("apps-command");
        problem.getStyleClass().add("card-problem");
        problem.setWrapText(true);
        problem.setMinHeight(Region.USE_PREF_SIZE);

        start.getStyleClass().addAll("primary-button", "compact-button");
        start.setMinWidth(Region.USE_PREF_SIZE);
        start.setOnAction(event -> {
            AppCommands.NewApp app = draft();
            if (AppCommands.problem(app) == null) {
                onStart.accept(app);
            }
        });
        Button cancel = AppsScope.action("cancel", event -> onClose.run());
        HBox.setHgrow(problem, Priority.ALWAYS);
        problem.setMaxWidth(Double.MAX_VALUE);
        HBox buttons = new HBox(16, problem, cancel, start);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        getChildren().addAll(top, form, new VBox(7, sends, sent), buttons);
        update();
    }

    /** Only for the previews: the form as somebody filled it. */
    void fillForPreview(String script, String port, int count) {
        path.setText(script);
        this.port.setText(port);
        workers = count;
        limit = 512;
        update();
    }

    /** Where browsing opens: beside what is typed already, or at home. */
    private String startFolder() {
        String typed = path.getText().strip();
        if (typed.startsWith("/") && typed.length() > 1) {
            if (npm) {
                return typed;
            }
            int slash = typed.lastIndexOf('/');
            return slash <= 0 ? "/" : typed.substring(0, slash);
        }
        return home.startsWith("/") ? home : null;
    }

    private Button workerChip(String text, int count) {
        Button chip = chip(text);
        chip.setUserData(count);
        chip.setOnAction(event -> {
            workers = count;
            update();
        });
        workerChoices.add(chip);
        return chip;
    }

    private AppCommands.NewApp draft() {
        int givenPort = 0;
        String typed = port.getText().strip();
        if (!typed.isEmpty()) {
            try {
                givenPort = Integer.parseInt(typed);
            } catch (NumberFormatException e) {
                givenPort = -1;
            }
        }
        return new AppCommands.NewApp(path.getText().strip(), npm, name.getText().strip(), workers, givenPort,
                save, limit);
    }

    private void update() {
        path.setPromptText(npm ? "/srv/site, the folder with package.json" : "/srv/api/server.js");
        light(kinds.get(0), !npm);
        light(kinds.get(1), npm);
        kindHint.setText(npm ? "the start script of a package.json: next, nuxt, nest and the like"
                : "one file, run by node, python or a shell");
        boolean node = !npm && path.getText().strip().matches(".*\\.(js|cjs|mjs)$");
        if (!node) {
            workers = 1;
        }
        for (Button chip : workerChoices) {
            int count = (int) chip.getUserData();
            light(chip, count == workers);
            chip.setDisable(count != 1 && !node);
        }
        int asked = workers == 0 ? Math.max(1, cores) : workers;
        workerHint.setText(!node ? "several workers need cluster mode, which only runs a node script"
                : asked == 1 ? "one process on one core"
                : asked + " processes share the port, and a reload restarts them one at a time");
        for (Button chip : limitChoices) {
            light(chip, (int) chip.getUserData() == limit);
        }
        limitHint.setText(limit == 0 ? "a leak grows until the machine runs out"
                : "a process that grows past it is restarted");
        light(saveChoice, save);
        saveChoice.setText(save ? "comes back after a reboot" : "gone after a reboot");

        AppCommands.NewApp app = draft();
        String trouble = path.getText().isBlank() ? null : AppCommands.problem(app);
        boolean ready = !path.getText().isBlank() && AppCommands.problem(app) == null;
        command.setText(ready ? "$ " + AppCommands.pm2Start(app) : "$ pm2 start …");
        problem.setText(trouble == null ? "" : trouble);
        start.setDisable(!ready);
    }

    /** The name of the script without its extension, or of the folder: what most people call it anyway. */
    private static String guessName(String typed) {
        String text = typed == null ? "" : typed.strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        String last = text.substring(text.lastIndexOf('/') + 1);
        if (last.matches("(server|index|main|app)\\.(js|cjs|mjs|ts|py)")) {
            String folder = text.substring(0, Math.max(0, text.lastIndexOf('/')));
            if (folder.endsWith("/dist") || folder.endsWith("/build") || folder.endsWith("/src")) {
                folder = folder.substring(0, folder.lastIndexOf('/'));
            }
            last = folder.substring(folder.lastIndexOf('/') + 1);
        }
        int dot = last.lastIndexOf('.');
        String name = (dot > 0 ? last.substring(0, dot) : last).replaceAll("[^A-Za-z0-9_.@-]", "-");
        return name.length() > 64 ? name.substring(0, 64) : name;
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
        key.setMinWidth(62);
        key.setPadding(new Insets(3, 0, 0, 0));
        return key;
    }
}
