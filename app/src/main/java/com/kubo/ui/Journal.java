package com.kubo.ui;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * What a wait looks like in Kubo: the journal of what is being done, the way a Linux machine reports its boot.
 *
 * <p>A red block says what is happening ({@code reading}, {@code connecting}), then who it is happening to.
 * Each answer comes back as a line, {@code [  OK  ]} or {@code [FAILED]}, with what it was in plain words and
 * how long it took, and under them a braille spinner turns while the next one is awaited. When it is over a
 * last line says so, and the journal fades.
 *
 * <p>Laid over whatever it is waiting to fill, which shows faintly through it, so a wait never empties the
 * screen. Every screen that reads the server uses it, each with its own words for its own commands.
 */
public final class Journal extends StackPane {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴",
            "⠦", "⠧", "⠇", "⠏"};
    /** How many lines stay on screen; older ones scroll off the top, the way a terminal does. */
    private static final int KEPT = 8;

    private final Function<String, String> words;
    private final Label mark = new Label();
    private final Label where = new Label();
    private final Label about = new Label();
    private final VBox lines = new VBox(3);
    private final Label spinner = new Label(FRAMES[0]);
    private final Label waiting = new Label();
    private final HBox waitingRow = new HBox(8, spinner, waiting);
    private final Timeline spin;
    private long startedAt;
    private boolean active;
    private int frame;
    private PauseTransition hold;
    private FadeTransition out;
    private String waitingText = "waiting for the server";

    /**
     * @param words what a command does, in a few words, or null for a command not worth a line
     */
    public Journal(Function<String, String> words) {
        this.words = words == null ? command -> null : words;
        getStyleClass().add("reading-overlay");
        mark.getStyleClass().add("reading-mark");
        where.getStyleClass().add("bash-host");
        about.getStyleClass().add("home-muted");
        about.setWrapText(true);
        about.setMaxWidth(460);
        spinner.getStyleClass().add("reading-spinner");
        waiting.getStyleClass().add("home-muted");
        waitingRow.setAlignment(Pos.CENTER_LEFT);

        HBox head = new HBox(10, mark, where);
        head.setAlignment(Pos.CENTER_LEFT);
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        VBox block = new VBox(10, head, about, rule, lines, waitingRow);
        block.getStyleClass().add("reading-block");
        block.setMinWidth(420);
        block.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        getChildren().add(block);

        spin = new Timeline(new KeyFrame(Duration.millis(80), event -> {
            frame = (frame + 1) % FRAMES.length;
            spinner.setText(FRAMES[frame]);
        }));
        spin.setCycleCount(Timeline.INDEFINITE);
        setVisible(false);
        setManaged(false);
    }

    /** True while a wait is on screen. */
    public boolean active() {
        return active;
    }

    /**
     * Starts showing a wait. Starting again while one is on screen keeps its lines and changes its words.
     *
     * @param action what is happening, one word: reading, connecting, installing
     * @param who    who it is happening to, as user@host
     * @param reason one line on why, or empty
     */
    public void start(String action, String who, String reason) {
        if (hold != null) {
            hold.stop();
        }
        if (out != null) {
            out.stop();
        }
        mark.setText(action);
        where.setText(who == null ? "" : who);
        about.setText(reason == null ? "" : reason);
        about.setVisible(reason != null && !reason.isBlank());
        about.setManaged(reason != null && !reason.isBlank());
        if (!active) {
            lines.getChildren().clear();
            startedAt = System.nanoTime();
        }
        active = true;
        waiting.setText(waitingText);
        waitingRow.setVisible(true);
        waitingRow.setManaged(true);
        setManaged(true);
        setVisible(true);
        spin.play();
        FadeTransition in = new FadeTransition(Duration.millis(160), this);
        in.setToValue(1);
        in.play();
    }

    /** What the spinner line says it is waiting for. */
    public void waitingFor(String text) {
        waitingText = text == null || text.isBlank() ? "waiting for the server" : text;
        waiting.setText(waitingText);
    }

    /** One command came back. Listed if its words say what it was. */
    public void answered(CommandEntry entry) {
        if (!active) {
            return;
        }
        String what = words.apply(entry.command());
        if (what == null) {
            return;
        }
        line(entry.success() ? "[  OK  ]" : entry.ran() ? "[FAILED]" : "[ SKIP ]",
                entry.success() ? "journal-ok" : entry.ran() ? "journal-error" : "journal-warning",
                what, entry.duration().toMillis() + "ms");
    }

    /** A step that is not a command, done. */
    public void done(String what, long millis) {
        if (active) {
            line("[  OK  ]", "journal-ok", what, millis < 0 ? "" : millis + "ms");
        }
    }

    /** A step that is not a command, failed. */
    public void failed(String what) {
        if (active) {
            line("[FAILED]", "journal-error", what, "");
        }
    }

    /**
     * Follows the commands of some screens while a wait is on screen, from whichever thread they finish on.
     *
     * @return stops following
     */
    public Runnable follow(CommandService commands, CommandService.Origin first, CommandService.Origin... more) {
        if (commands == null) {
            return () -> { };
        }
        Set<CommandService.Origin> origins = EnumSet.of(first, more);
        return commands.addLoggedListener(logged -> {
            if (origins.contains(logged.origin())) {
                CommandEntry entry = logged.entry();
                Platform.runLater(() -> answered(entry));
            }
        });
    }

    /**
     * The wait is over: one last line, then the journal goes.
     *
     * @param what    what was done, said on the last line: read, connected, scanned
     * @param trouble why it stopped, or null when it finished
     */
    public void finish(String what, String trouble) {
        if (!active) {
            return;
        }
        active = false;
        spin.stop();
        waitingRow.setVisible(false);
        waitingRow.setManaged(false);
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        if (trouble == null) {
            line("[  OK  ]", "journal-ok", what, String.format(Locale.ROOT, "%.1fs", seconds));
        } else {
            line("[FAILED]", "journal-error", trouble, "");
        }
        hold = new PauseTransition(Duration.millis(trouble == null ? 450 : 2800));
        out = new FadeTransition(Duration.millis(260), this);
        out.setToValue(0);
        hold.setOnFinished(event -> out.play());
        out.setOnFinished(event -> {
            if (!active) {
                setVisible(false);
                setManaged(false);
            }
        });
        hold.play();
    }

    /** Goes at once, without a last line: the wait was abandoned, or something else took its place. */
    public void dismiss() {
        active = false;
        spin.stop();
        if (hold != null) {
            hold.stop();
        }
        if (out != null) {
            out.stop();
        }
        setVisible(false);
        setManaged(false);
    }

    private void line(String state, String style, String what, String took) {
        Label verdict = label(state, style);
        verdict.setMinWidth(Region.USE_PREF_SIZE);
        Label name = label(what, "fetch-value");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label time = label(took, "home-muted");
        time.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(12, verdict, name, time);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOpacity(0);
        row.setTranslateX(8);
        lines.getChildren().add(row);
        while (lines.getChildren().size() > KEPT) {
            lines.getChildren().removeFirst();
        }
        FadeTransition fade = new FadeTransition(Duration.millis(180), row);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), row);
        slide.setToX(0);
        new ParallelTransition(fade, slide).play();
    }

    /** Only for the previews: a wait part way through, with its lines already in. */
    public void showForPreview(String action, String who, String reason, java.util.List<CommandEntry> answered) {
        start(action, who, reason);
        answered.forEach(this::answered);
        settleForPreview();
    }

    /** Only for the previews: every line as it ends up, without the animation in between. */
    public void settleForPreview() {
        lines.getChildren().forEach(row -> {
            row.setOpacity(1);
            row.setTranslateX(0);
        });
        setOpacity(1);
        spin.stop();
        spinner.setText(FRAMES[3]);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
