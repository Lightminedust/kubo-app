package com.kubo.ui.services;

import com.kubo.model.services.Action;
import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.DropIn;
import com.kubo.model.services.Lifeline;
import com.kubo.model.services.Removal;
import com.kubo.model.services.Resource;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.Signal;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One service in full, beside the tree: the table, what it depends on, what can be done to it, the settings
 * it has been given, its journal, and its unit file.
 *
 * <p>systemd answers {@code systemctl show} with something like two hundred properties, nearly all of them
 * defaults nobody set. This card names the dozen that say what the service is and what it has been doing, in
 * the words a person would use, and leaves the rest where it is.
 */
final class ServiceSheet extends VBox {

    /**
     * Everything this card asks the server to do.
     *
     * <p>The card holds no connection of its own. It says what was asked for and shows what came back; the
     * screen above it owns the sending, the root password and the refusals.
     */
    interface Hands {

        void act(Action action);

        /** @param done called with null when it worked, or with the reason it did not */
        void limit(Resource resource, String value, Consumer<String> done);

        void signal(Signal signal, boolean everything, Consumer<String> done);

        void saveSettings(String content, Consumer<String> done);

        void clearSettings(Consumer<String> done);

        /** @param back called with whatever settings this service already has, or an empty string */
        void readSettings(Consumer<String> back);

        /**
         * Takes the service off the machine altogether. The screen above asks first.
         *
         * @param filePath the unit file systemd says it reads, which is what decides whether it may go
         */
        void remove(String filePath);
    }

    /** The properties worth showing, in the order they answer "what is this thing doing?". */
    private static final String[][] SHOWN = {
        {"Description", "WHAT IT IS"},
        {"Type", "HOW IT STARTS"},
        {"ExecStart", "WHAT IT RUNS"},
        {"FragmentPath", "ITS UNIT FILE"},
        {"MainPID", "PROCESS"},
        {"MemoryCurrent", "MEMORY NOW"},
        {"MemoryMax", "MEMORY LIMIT"},
        {"CPUQuotaPerSecUSec", "CPU LIMIT"},
        {"TasksCurrent", "THREADS"},
        {"ActiveEnterTimestamp", "UP SINCE"},
        {"NRestarts", "RESTARTS"},
        {"Restart", "ON FAILURE"},
        {"Result", "HOW IT ENDED"},
        {"StatusText", "WHAT IT SAYS"},
    };

    private static final String SETTINGS_START = DropIn.SECTION + "\n";

    private final ServiceUnit unit;
    private final boolean canAct;
    private final Hands hands;
    private final VBox tableSlot = new VBox(8);
    private final TextArea file = new TextArea();
    private final TextArea journal = new TextArea();
    private final TextArea settings = new TextArea();
    private final Label reading = label("READING THE UNIT", "card-note");
    /** Whether this service already has a file of its own, and where it is. */
    private final Label settingsPath = label("", "mono-muted");
    private final ProgressIndicator spinner = new ProgressIndicator();
    /** Empty until the unit is read: what can be deleted is not known before its file is. */
    private final VBox removalSlot = new VBox(10);
    private final Label status = label("", "card-note");

    /**
     * @param canAct false when this account cannot change anything, in which case nothing that changes the
     *               server is offered at all rather than offered and failing
     */
    ServiceSheet(ServiceUnit unit, Dependencies dependencies, boolean canAct, Hands hands, Runnable onClose) {
        this.unit = unit;
        this.canAct = canAct;
        this.hands = hands;
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(12);
        setPadding(new Insets(20, 26, 20, 24));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());

        Label badge = label(unit.standing().label(), "badge");
        String colour = web(ServicesTree.colour(unit.standing()));
        badge.setStyle("-fx-text-fill: " + colour + "; -fx-border-color: " + colour + ";");
        HBox header = new HBox(12, label(unit.shortName(), "card-title"), badge, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        // A turning indicator rather than a line of text: a card that says "reading" and never moves is a
        // card that looks stuck.
        spinner.getStyleClass().add("connection-progress");
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        HBox busy = new HBox(10, spinner, reading);
        busy.setAlignment(Pos.CENTER_LEFT);
        tableSlot.getChildren().add(busy);

        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        dump(file, 200);
        dump(journal, 190);
        journal.setText("");

        VBox body = new VBox(14, label(unit.name(), "series"),
                note(unit.standing().meaning().toUpperCase(Locale.ROOT)),
                label("THE TABLE", "series"), tableSlot, status,
                dependencyBlock(unit, dependencies),
                actionBlock(), limitBlock(), signalBlock(), settingsBlock(), removalSlot,
                label("THE LAST 200 LINES IT WROTE", "series"), journal,
                label("THE UNIT FILE, AS IT IS ON DISK", "series"), file);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(620);
        scroll.setMaxWidth(700);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    // What comes back from the server

    /** Fills the card once the two reads come back. */
    void setDetail(Map<String, String> properties, String unitFile) {
        GridPane table = new GridPane();
        table.setHgap(20);
        table.setVgap(7);
        int row = 0;
        for (String[] shown : SHOWN) {
            String value = pretty(shown[0], properties.get(shown[0]));
            if (value == null || value.isBlank()) {
                continue;
            }
            Label name = label(shown[1], "metric-label");
            name.setMinWidth(150);
            table.add(name, 0, row);
            Label text = label(value, "mono-muted");
            text.setWrapText(true);
            text.setMaxWidth(460);
            table.add(text, 1, row);
            row++;
        }
        tableSlot.getChildren().setAll(row == 0
                ? label("SYSTEMD ANSWERED NOTHING ABOUT THIS UNIT.", "card-note") : table);
        file.setText(unitFile == null || unitFile.isBlank()
                ? "systemctl cat answered nothing: this unit may have no file of its own." : unitFile.trim());
        fillRemoval(properties.getOrDefault("FragmentPath", ""));
    }

    /**
     * Whether this service can be taken off the machine, answered once its file is known.
     *
     * <p>Nothing is offered before the read comes back. A delete button drawn before Kubo knows which file
     * systemd is reading would be a button offering something it cannot yet judge, and this is the one verb
     * that has nothing behind it: the file is gone, not disabled.
     */
    private void fillRemoval(String filePath) {
        if (!canAct) {
            removalSlot.getChildren().clear();
            return;
        }
        Label heading = label("TAKING IT OFF THE MACHINE", "series");
        String refusal = Removal.refusal(unit.name(), filePath);
        if (refusal != null) {
            // The reason rather than a greyed-out button: the reason is the useful half.
            removalSlot.getChildren().setAll(heading, note(refusal));
            return;
        }
        Button delete = new Button("DELETE IT");
        delete.getStyleClass().addAll("danger-button", "compact-button");
        delete.setFocusTraversable(false);
        delete.setTooltip(new Tooltip("Stops it, disables it, and deletes " + filePath));
        delete.setOnAction(event -> hands.remove(filePath));
        removalSlot.getChildren().setAll(heading,
                note("THIS DELETES  " + filePath + "  AND ANY SETTINGS KUBO WROTE BESIDE IT. NOTHING "
                        + "UNDOES IT."),
                delete);
    }

    /** The unit's own journal, as the server answered it. */
    void setJournal(String text) {
        journal.setText(text == null || text.isBlank()
                ? "This service has written nothing to the journal." : text.strip());
    }

    /**
     * Whatever settings this service already has, and whether it has any at all.
     *
     * <p>An empty editor could mean either "there is no such file" or "it has not been read yet", and those
     * are not the same thing to somebody about to press save.
     */
    void setSettings(String text) {
        boolean exists = text != null && !text.isBlank();
        settings.setText(exists ? text.strip() + "\n" : SETTINGS_START);
        String path = com.kubo.services.ServiceCommands.dropInFile(unit.name());
        settingsPath.setText(exists ? "IT HAS ONE:  " + path
                : "IT HAS NONE YET.  ANYTHING SAVED HERE GOES TO  " + path);
        settingsPath.getStyleClass().setAll("label", exists ? "mono" : "mono-muted");
    }

    /** A read failed. The card says why rather than sitting on a spinner for good. */
    void setProblem(String reason) {
        spinner.setVisible(false);
        spinner.setManaged(false);
        reading.setText("COULD NOT READ THIS UNIT: " + reason);
        reading.getStyleClass().setAll("label", "card-problem");
        file.setText("");
    }

    /** What came of the last thing asked for, in a line, kept until the next one. */
    private void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    // The blocks that change the server

    /**
     * The five verbs, each offered only where it would do something.
     *
     * <p>A button is missing rather than greyed out when it cannot be run at all: greyed out invites a click
     * and explains nothing. The one case that gets a sentence instead of a button is the service carrying
     * this very connection, because that refusal is the interesting thing on the card.
     */
    private VBox actionBlock() {
        VBox box = new VBox(10, label("WHAT YOU CAN DO", "series"));
        if (!canAct) {
            box.getChildren().add(note("CHANGING A SERVICE NEEDS ROOT. TURN ON ROOT IN THE HEADER AND CLICK "
                    + "THIS SERVICE AGAIN."));
            return box;
        }
        FlowPane buttons = new FlowPane(8, 8);
        for (Action action : Action.values()) {
            if (Lifeline.refusal(action, unit.name()) != null || !worthOffering(action, unit)) {
                continue;
            }
            Button button = new Button(action.label());
            button.getStyleClass().addAll(action.takesAway() ? "danger-button" : "rail-button", "compact-button");
            button.setFocusTraversable(false);
            button.setTooltip(new Tooltip(action.meaning()));
            button.setOnAction(event -> hands.act(action));
            buttons.getChildren().add(button);
        }
        box.getChildren().add(buttons);
        String refusal = Lifeline.refusal(Action.STOP, unit.name());
        if (refusal != null) {
            box.getChildren().add(note(refusal.toUpperCase(Locale.ROOT)));
        }
        return box;
    }

    /**
     * A limit put on a service without stopping it, which is the answer to "this thing is eating the machine
     * and I need it held back until I can look at it".
     */
    private VBox limitBlock() {
        if (!canAct) {
            return new VBox();
        }
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        int row = 0;
        for (Resource resource : Resource.values()) {
            TextField field = new TextField();
            field.getStyleClass().add("card-field");
            field.setPromptText(resource.meaning());
            field.setPrefColumnCount(10);
            Button set = new Button("SET");
            set.getStyleClass().addAll("rail-button", "compact-button");
            set.setFocusTraversable(false);
            Runnable send = () -> {
                String value = field.getText().strip();
                // Judged here, before anything is sent: a value systemd would not take is a value Kubo does
                // not put in a command.
                if (!resource.accepts(value)) {
                    report(resource.label() + " does not take “" + value + "”. "
                            + resource.meaning(), null);
                    return;
                }
                set.setDisable(true);
                set.setText("WORKING…");
                hands.limit(resource, value, trouble -> {
                    set.setDisable(false);
                    set.setText("SET");
                    report(trouble, resource.label() + " SET TO " + value.toUpperCase(Locale.ROOT));
                });
            };
            set.setOnAction(event -> send.run());
            field.setOnAction(event -> send.run());

            Label name = label(resource.label(), "metric-label");
            name.setMinWidth(150);
            grid.add(name, 0, row);
            grid.add(field, 1, row);
            grid.add(set, 2, row);
            row++;
        }
        return new VBox(10, label("HOLDING IT BACK", "series"), grid,
                note("SYSTEMD APPLIES THESE AT ONCE, WITHOUT A RESTART, AND KEEPS THEM ACROSS ONE. SET "
                        + "EITHER TO INFINITY TO TAKE THE LIMIT OFF AGAIN."));
    }

    /** A signal straight to the service, without the stop that usually carries one. */
    private VBox signalBlock() {
        if (!canAct) {
            return new VBox();
        }
        FlowPane buttons = new FlowPane(8, 8);
        for (Signal signal : Signal.values()) {
            if (Lifeline.refusal(signal, unit.name()) != null) {
                continue;
            }
            Button button = new Button(signal.signalName());
            button.getStyleClass().addAll(signal.rough() ? "danger-button" : "rail-button", "compact-button");
            button.setFocusTraversable(false);
            button.setTooltip(new Tooltip(signal.meaning()));
            button.setOnAction(event -> {
                button.setDisable(true);
                hands.signal(signal, signal == Signal.KILL, trouble -> {
                    button.setDisable(false);
                    report(trouble, signal.signalName() + " SENT");
                });
            });
            buttons.getChildren().add(button);
        }
        return new VBox(10, label("SENDING IT A SIGNAL", "series"), buttons,
                note("SIGKILL GOES TO EVERYTHING IN THE SERVICE'S GROUP; THE OTHERS GO TO ITS MAIN PROCESS. "
                        + "NONE OF THEM CHANGES WHETHER SYSTEMD CONSIDERS THE SERVICE RUNNING."));
    }

    /**
     * The service's own settings.
     *
     * <p>The file a distribution installed is never touched. systemd reads a drop-in after it, so what is
     * written here wins, and taking it away puts the service back exactly as the package left it. That is the
     * only safe way to let anybody edit a unit from a screen.
     */
    private VBox settingsBlock() {
        if (!canAct) {
            return new VBox();
        }
        settings.setWrapText(false);
        settings.getStyleClass().add("redis-diagnostic-output");
        settings.setPrefSize(640, 130);
        settings.setText(SETTINGS_START);
        hands.readSettings(this::setSettings);

        Button save = new Button("SAVE AND RELOAD");
        save.getStyleClass().addAll("rail-button", "compact-button");
        save.setFocusTraversable(false);
        Button clear = new Button("REMOVE THEM");
        clear.getStyleClass().addAll("danger-button", "compact-button");
        clear.setFocusTraversable(false);

        save.setOnAction(event -> {
            String content = settings.getText();
            if (content.isBlank()) {
                report("There is nothing to save. Use REMOVE THEM to take the settings away.", null);
                return;
            }
            save.setDisable(true);
            save.setText("WORKING…");
            hands.saveSettings(content, trouble -> {
                save.setDisable(false);
                save.setText("SAVE AND RELOAD");
                report(trouble, "SETTINGS SAVED AND SYSTEMD RELOADED");
            });
        });
        clear.setOnAction(event -> {
            clear.setDisable(true);
            hands.clearSettings(trouble -> {
                clear.setDisable(false);
                if (trouble == null) {
                    settings.setText(SETTINGS_START);
                }
                report(trouble, "SETTINGS REMOVED, THE SERVICE IS BACK AS ITS PACKAGE LEFT IT");
            });
        });

        HBox buttons = new HBox(10, save, clear);
        buttons.setAlignment(Pos.CENTER_LEFT);
        settingsPath.setWrapText(true);
        settingsPath.setMaxWidth(600);
        return new VBox(10, label("ITS OWN SETTINGS", "series"),
                note("WRITTEN TO A FILE OF ITS OWN BESIDE THE ONE YOUR PACKAGE SHIPPED, WHICH IS NEVER "
                        + "TOUCHED. WHAT IS HERE WINS OVER IT, AND REMOVING IT PUTS THE SERVICE BACK EXACTLY "
                        + "AS IT WAS. A SETTING THAT ONLY TAKES EFFECT ON A RESTART STILL NEEDS ONE."),
                settingsPath, crashBlock(), settings, buttons);
    }

    /**
     * The one setting people come here for: what systemd does when the service dies.
     *
     * <p>These write the line into the editor below rather than sending anything, so what is about to be
     * saved is on the screen before it is saved. Several changes are one file at the end: the line is
     * replaced where it already is, and everything else in the file is left where it was.
     */
    private HBox crashBlock() {
        HBox row = new HBox(8, label("WHEN IT DIES", "metric-label"));
        row.setAlignment(Pos.CENTER_LEFT);
        String[][] policies = {{"no", "NEVER RESTART"}, {"on-failure", "RESTART ON FAILURE"},
            {"always", "ALWAYS RESTART"}};
        for (String[] policy : policies) {
            Button button = new Button(policy[1]);
            button.getStyleClass().addAll("rail-button", "compact-button");
            button.setFocusTraversable(false);
            button.setTooltip(new Tooltip("Writes Restart=" + policy[0] + " below. Press SAVE to apply it."));
            button.setOnAction(event -> {
                settings.setText(DropIn.with(settings.getText(), "Restart", policy[0]));
                report(null, "WRITTEN BELOW. PRESS SAVE AND RELOAD TO APPLY IT.");
            });
            row.getChildren().add(button);
        }
        return row;
    }

    /**
     * Whether this verb would change anything. Starting what is already up, or enabling what already comes
     * back, is a button that does nothing and says nothing.
     */
    private static boolean worthOffering(Action action, ServiceUnit unit) {
        return switch (action) {
            case START -> !unit.active();
            case STOP, RESTART -> unit.active();
            case ENABLE -> !unit.boot().returnsAfterReboot();
            case DISABLE -> unit.boot() == BootState.ENABLED;
        };
    }

    /**
     * systemd's raw values, in the words a person uses: a byte count is a size, an unset property is left out
     * rather than shown as the largest number a computer can hold, and ExecStart is a whole structure of which
     * only the command line matters.
     */
    private static String pretty(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.equals("[not set]") || value.equals("18446744073709551615")) {
            return null;
        }
        if (key.equals("MainPID") && value.equals("0")) {
            return null;
        }
        return switch (key) {
            case "MemoryCurrent", "MemoryMax" -> size(value);
            case "CPUQuotaPerSecUSec" -> quota(value);
            case "ExecStart" -> commandLine(value);
            case "NRestarts" -> value.equals("0") ? null : value + "  (since it was last started)";
            default -> value;
        };
    }

    /** systemd answers a quota as microseconds of processor per second. A person reads it as a percentage. */
    private static String quota(String value) {
        String trimmed = value.trim();
        if (trimmed.equals("infinity")) {
            return null;
        }
        try {
            long micros = Long.parseLong(trimmed.replace("us", "").trim());
            return Math.round(micros / 10_000.0) + "%";
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    /** ExecStart reads {@code { path=... ; argv[]=... ; ignore_errors=no ... }}: only the argv is the command. */
    private static String commandLine(String value) {
        int start = value.indexOf("argv[]=");
        if (start < 0) {
            return value;
        }
        int end = value.indexOf(" ; ", start);
        return value.substring(start + "argv[]=".length(), end < 0 ? value.length() : end).trim();
    }

    private static String size(String value) {
        String trimmed = value.trim();
        if (trimmed.equals("infinity")) {
            return "no limit";
        }
        long bytes;
        try {
            bytes = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed;
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(unit == 0 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }

    /**
     * What this service leans on, and what leans on it. The second list is the one to read before stopping
     * anything.
     */
    private static VBox dependencyBlock(ServiceUnit unit, Dependencies dependencies) {
        List<String> needs = dependencies.needsOf(unit.name());
        List<String> neededBy = dependencies.neededByOf(unit.name());
        VBox box = new VBox(8);
        if (needs.isEmpty() && neededBy.isEmpty()) {
            return box;
        }
        List<Node> rows = new ArrayList<>();
        rows.add(label("WHAT IT DEPENDS ON", "series"));
        rows.add(line("IT NEEDS", needs));
        rows.add(line("IT IS NEEDED BY", neededBy));
        box.getChildren().addAll(rows);
        return box;
    }

    private static HBox line(String caption, List<String> units) {
        Label name = label(caption, "metric-label");
        name.setMinWidth(150);
        List<String> shortened = new ArrayList<>();
        for (String unit : units) {
            shortened.add(unit.endsWith(".service") ? unit.substring(0, unit.length() - ".service".length()) : unit);
        }
        Label value = label(shortened.isEmpty() ? "NOTHING" : String.join("  ·  ", shortened), "mono-muted");
        value.setWrapText(true);
        value.setMaxWidth(460);
        HBox row = new HBox(20, name, value);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static void dump(TextArea area, double height) {
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("redis-diagnostic-output");
        area.setPrefSize(640, height);
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(600);
        return label;
    }

    private static String web(Color colour) {
        return String.format("#%02x%02x%02x", (int) Math.round(colour.getRed() * 255),
                (int) Math.round(colour.getGreen() * 255), (int) Math.round(colour.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
