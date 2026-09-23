package com.kubo.ui;

import com.kubo.model.tools.InstallProgress;
import com.kubo.model.tools.InstallProgress.Phase;
import com.kubo.ui.scope.Format;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * An install as it happens: how far it is, how much has come over, and the server's own words.
 *
 * <p>Built to read like a transfer in FILES, because it is one. A package manager fetches over the network,
 * unpacks and configures, and it says so in figures the whole time. A line sliding back and forth would say only
 * that something is happening.
 *
 * <p>Nothing here is invented. When the manager gives no figures the bar sweeps instead of filling, and the
 * lines and the clock are what is shown: a bar creeping toward the end on a timer would be a lie about a thing
 * the operator is waiting on.
 *
 * <p>It fills the row it is in and never widens it. A package manager prints lines longer than any card, and a
 * box that took its width from its widest line pushed the card wider with every line that arrived, and stretched
 * the bar with it until sixty four dots were spread over three screens and the animation could not be seen.
 */
public final class InstallReadout extends VBox {

    private static final Color FETCH = Color.web("#35e8ff");
    private static final Color DONE = Color.web("#35e0a1");
    private static final Color FAILED = Color.web("#ff2d55");
    private static final Color TRACK = Color.web("#2a2a34");
    private static final int DOTS = 64;
    /** The padding on the left and the right together. */
    private static final double SIDES = 26;

    private final Canvas bar = new Canvas(0, 12);
    private final Label phase = label("", "install-phase");
    private final Label subject = label("", "mono-muted");
    private final Label figures = label("", "mono");
    private final VBox lines = new VBox(1);
    private final LiveTimer timer;

    private final String tool;
    private final String manager;
    private InstallProgress progress = new InstallProgress();
    private long startedAt = System.nanoTime();
    private boolean running;
    /** The room inside the padding, from the last time this was laid out. */
    private double inner;

    /**
     * @param tool    the binary being installed, which is what the operator asked for
     * @param manager the package manager driving it
     */
    public InstallReadout(String tool, String manager) {
        super(7);
        this.tool = tool;
        this.manager = manager;
        getStyleClass().add("install-readout");
        setPadding(new Insets(11, 13, 12, 13));

        // Asks for nothing and accepts everything: in a column this fills the width it is given and has no say
        // in what that width is.
        setMinWidth(0);
        setPrefWidth(0);
        setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(12, phase, spacer, subject);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinWidth(0);
        head.setPrefWidth(0);
        head.setMaxWidth(Double.MAX_VALUE);

        lines.getStyleClass().add("install-lines");
        lines.setMinWidth(0);
        lines.setPrefWidth(0);
        lines.setMaxWidth(Double.MAX_VALUE);
        figures.setMinWidth(0);

        widthProperty().addListener((observable, before, now) -> {
            inner = Math.max(0, now.doubleValue() - SIDES);
            bar.setWidth(inner);
            resizeLines();
            paint(0);
        });

        getChildren().addAll(head, bar, figures, lines);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, this::paint);
        write();
    }

    /** Starts the clock and the animation. Called once, when the command goes out. */
    public void start() {
        startedAt = System.nanoTime();
        running = true;
        progress = new InstallProgress();
        timer.start();
        write();
    }

    /**
     * Takes one line the server printed. Called on the JavaFX thread only.
     */
    public void accept(String line) {
        progress.accept(line);
        write();
    }

    /**
     * Says the command came back. The clock stops here; what it printed stays on screen.
     */
    public void finish(boolean worked) {
        progress.finish(worked);
        running = false;
        timer.stop();
        write();
        paint(0);
    }

    public InstallProgress progress() {
        return progress;
    }

    /** Only for the previews: fills the readout without a server. */
    public void showForPreview(List<String> printed, boolean finished, boolean worked) {
        progress = new InstallProgress();
        printed.forEach(progress::accept);
        if (finished) {
            progress.finish(worked);
        }
        running = !finished;
        write();
        paint(1.4);
    }

    private void write() {
        phase.setText(progress.phaseName());
        phase.getStyleClass().setAll("label", switch (progress.phase()) {
            case DONE -> "card-success";
            case FAILED -> "card-problem";
            default -> "install-phase";
        });
        subject.setText(tool + "  ·  " + manager + "  ·  " + elapsed());
        figures.setText(figureLine());
        figures.setVisible(!figures.getText().isEmpty());
        figures.setManaged(!figures.getText().isEmpty());

        lines.getChildren().clear();
        List<String> tail = progress.tail();
        for (int i = 0; i < tail.size(); i++) {
            Label line = label(tail.get(i), "install-line");
            line.setMinWidth(0);
            // Cut in the middle rather than allowed to widen the box: the start of one of these lines says what
            // is being fetched and the end says how big it is, and the middle of it is a mirror path.
            line.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            // The newest line is the one being read; the ones above it fade rather than compete with it.
            line.setOpacity(0.35 + 0.65 * ((i + 1.0) / tail.size()));
            lines.getChildren().add(line);
        }
        resizeLines();
        lines.setVisible(!tail.isEmpty());
        lines.setManaged(!tail.isEmpty());
    }

    private void resizeLines() {
        if (inner <= 0) {
            return;
        }
        figures.setMaxWidth(inner);
        for (javafx.scene.Node node : lines.getChildren()) {
            if (node instanceof Label line) {
                line.setMaxWidth(inner);
            }
        }
    }

    /**
     * The figures the manager gave, and nothing else. Empty until it gives some.
     */
    private String figureLine() {
        StringBuilder text = new StringBuilder();
        if (progress.fetched() > 0) {
            text.append(Format.bytes(progress.fetched()));
            if (progress.total() > progress.fetched()) {
                text.append(" of ").append(Format.bytes(progress.total()));
            }
        } else if (progress.total() > 0) {
            text.append(Format.bytes(progress.total())).append(" to fetch");
        }
        if (progress.packages() > 0) {
            append(text, progress.packages() == 1 ? "1 package" : progress.packages() + " packages");
        }
        if (!progress.speed().isEmpty()) {
            append(text, progress.speed());
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String piece) {
        if (text.length() > 0) {
            text.append("  ·  ");
        }
        text.append(piece);
    }

    private String elapsed() {
        long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    /**
     * The bar: a row of dots lit as the bytes arrive, with the leading one glowing.
     *
     * <p>When the manager gives no figures the dots sweep instead, which says the same thing a percentage would
     * have said falsely: something is happening, and nobody here knows how much is left.
     */
    private void paint(double seconds) {
        double width = bar.getWidth();
        double height = bar.getHeight();
        if (width < 1) {
            return;
        }
        GraphicsContext gc = bar.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);

        Phase state = progress.phase();
        Color lit = switch (state) {
            case DONE -> DONE;
            case FAILED -> FAILED;
            default -> FETCH;
        };
        double shown = progress.fraction();
        double step = width / DOTS;
        int litCount = shown < 0 ? 0 : (int) Math.floor(shown * DOTS);

        for (int i = 0; i < DOTS; i++) {
            double x = i * step + step / 2;
            double size;
            double alpha;
            Color colour;
            if (shown < 0 && running) {
                // Nothing to measure: a wave running along the track.
                double wave = 0.5 + 0.5 * Math.sin(seconds * 5 - i * 0.35);
                colour = lit;
                alpha = 0.12 + 0.55 * wave;
                size = 1.6 + wave;
            } else if (i < litCount) {
                colour = lit;
                alpha = 0.95;
                size = 3;
            } else {
                colour = TRACK;
                alpha = 1;
                size = 1.6;
            }
            double y = height / 2 + (running && shown >= 0 && i < litCount
                    ? Math.sin(i * 0.9 + seconds * 3) * 1.5 : 0);
            gc.setFill(colour);
            gc.setGlobalAlpha(alpha);
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }

        if (running && shown >= 0 && litCount < DOTS) {
            double x = litCount * step + step / 2;
            gc.setFill(Color.WHITE);
            gc.setGlobalAlpha(0.3);
            gc.fillOval(x - 6, height / 2 - 6, 12, 12);
            gc.setGlobalAlpha(1);
            gc.fillOval(x - 2, height / 2 - 2, 4, 4);
        }
        gc.setGlobalAlpha(1);

        if (running) {
            // The clock is part of the reading, so it is rewritten with the frame rather than on a timer of
            // its own.
            subject.setText(tool + "  ·  " + manager + "  ·  " + elapsed());
        }
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
