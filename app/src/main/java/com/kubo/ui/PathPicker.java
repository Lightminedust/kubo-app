package com.kubo.ui;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.PathKind;
import com.kubo.model.files.RemoteEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Choosing a path on the server by walking to it, in the look of LOCAL.SCOPE: a folder, a certificate, a key, a
 * socket.
 *
 * <p>Typing {@code /etc/letsencrypt/live/api.gemmi-e.com/fullchain.pem} from memory is how a site ends up
 * refusing to start. Walking there is how you know it is the file that exists. Everything in a folder is shown,
 * so the reader can tell they are in the right one, and only what fits the {@link PathKind} can be chosen: the
 * rest is drawn grey.
 *
 * <p>It knows nothing about where the listing comes from. Whoever opens it hands it a {@link Lister}: the
 * network screen lists through its own connection, and anything else that needs a path can do the same.
 */
public final class PathPicker extends VBox {

    /** Reads one folder. Answers on the JavaFX thread. */
    public interface Lister {
        void list(String path, Consumer<DirectoryListing> done);
    }

    /**
     * Reads the armour line of one file, the {@code -----BEGIN …-----} line, and nothing more. Answers on the
     * JavaFX thread, with an empty string when there is none.
     */
    public interface Peeker {
        void peek(String path, Consumer<String> armour);
    }

    private static final Color SERVER = Color.web("#9d4dff");
    private static final Color FITS = Color.web("#35e0a1");
    private static final Color OTHER = Color.web("#4a4a57");

    private final PathKind kind;
    private final Lister lister;
    private final SpineList list = new SpineList(SERVER);
    private final Crumbs crumbs;
    private final Label folder = label("", "files-title");
    private final Label picked = label("", "metric-label");
    private final Label into = label("", "mono-muted");
    private final Button choose = new Button();

    private String current = "/";
    private List<RemoteEntry> shown = List.of();
    private Peeker peeker;
    /** What each file picked so far turned out to be, so picking it again asks nothing. */
    private final java.util.Map<String, String> armours = new java.util.HashMap<>();
    private final Consumer<String> onChoose;

    /**
     * @param start    where it opens: whatever the field already says, or the kind's usual place
     * @param onChoose called with the path chosen
     */
    public PathPicker(String title, PathKind kind, Lister lister, String start, Consumer<String> onChoose,
                      Runnable onClose) {
        this.kind = kind;
        this.lister = lister;
        this.onChoose = onChoose;
        this.crumbs = new Crumbs(this::open);
        getStyleClass().addAll("float-card", "server-card");
        setSpacing(10);
        setPadding(new Insets(18, 20, 16, 20));
        setPrefWidth(440);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox top = new HBox(10, new VBox(2, label("SERVER.SCOPE", "spine-kicker"), label(title, "card-title")),
                spacer, close);
        top.setAlignment(Pos.TOP_LEFT);

        FlowPane shortcuts = new FlowPane(12, 4);
        for (String place : kind.places()) {
            Button button = new Button(place);
            button.getStyleClass().add("spine-place");
            button.setFocusTraversable(false);
            button.setOnAction(event -> open(place));
            shortcuts.getChildren().add(button);
        }

        list.setPrefHeight(300);
        list.setMinHeight(220);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setOnPick(this::picked);
        list.setOnOpen(index -> open(shown.get(index).path()));
        list.setOnEnter(index -> take());
        list.setOnBack(() -> {
            if (!current.equals("/")) {
                open(Crumbs.parent(current));
            }
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });

        choose.getStyleClass().addAll("primary-button", "compact-button");
        choose.setFocusTraversable(false);
        choose.setMinWidth(Region.USE_PREF_SIZE);
        into.setMinWidth(0);
        into.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        choose.setOnAction(event -> take());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bottom = new HBox(12, new VBox(2, picked, into), gap, choose);
        bottom.setAlignment(Pos.CENTER_LEFT);

        Label help = label(kind.wantsFolder()
                ? "CLICK A FOLDER TO PICK IT  ·  DOUBLE-CLICK OR › TO GO IN  ·  THE DOTS ARE FILES, SHOWN SO YOU "
                        + "CAN TELL IT IS THE RIGHT FOLDER  ·  ARROWS, ENTER, BACKSPACE AND ESC WORK TOO"
                : "CLICK A FOLDER TO GO IN  ·  CLICK A GREEN DOT TO PICK IT  ·  GREY IS NOT A "
                        + kind.label() + "  ·  ARROWS, ENTER, BACKSPACE AND ESC WORK TOO", "vertical-note");
        help.setWrapText(true);

        getChildren().addAll(top, shortcuts, crumbs, folder, list, bottom, help);
        showPicked();
        open(start == null || !start.startsWith("/") ? kind.start() : start);
    }

    /** Goes into a folder. */
    public void open(String path) {
        current = path;
        crumbs.show(path);
        folder.setText(Crumbs.name(path));
        list.setLoading(true);
        showPicked();
        lister.list(path, listing -> {
            if (!path.equals(current)) {
                return;
            }
            if (listing.failed()) {
                fill(List.of());
                list.setMessage("CANNOT OPEN THIS FOLDER: " + listing.error().toUpperCase(Locale.ROOT)
                        + (listing.error().toLowerCase(Locale.ROOT).contains("denied")
                        ? "  ·  TURN ON ROOT" : ""), true);
                return;
            }
            List<RemoteEntry> entries = new ArrayList<>(listing.entries());
            entries.removeIf(entry -> entry.name().startsWith("."));
            fill(entries);
            list.setMessage("EMPTY FOLDER", false);
            list.focus();
        });
    }

    /**
     * Lets the picker read the first line of a file it is offered, to check it is what its name says. Without
     * one, the name is all it goes on.
     */
    public void setPeeker(Peeker peeker) {
        this.peeker = peeker;
    }

    /** Hands back what is picked, if it may be taken. */
    private void take() {
        if (!choose.isDisabled()) {
            String path = chosen();
            if (path != null) {
                onChoose.accept(path);
            }
        }
    }

    private void fill(List<RemoteEntry> entries) {
        List<RemoteEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((RemoteEntry entry) -> !entry.isDirectory())
                .thenComparing((RemoteEntry entry) -> !kind.accepts(entry))
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        shown = List.copyOf(sorted);
        List<SpineList.Row> rows = new ArrayList<>();
        for (RemoteEntry entry : shown) {
            rows.add(row(entry));
        }
        list.setRows(rows);
        showPicked();
    }

    /** A folder is a ring; what can be chosen is green; everything else is a grey dot, named for what it is. */
    private SpineList.Row row(RemoteEntry entry) {
        if (entry.isDirectory()) {
            return new SpineList.Row(entry.name(), "", true, null);
        }
        if (!kind.wantsFolder() && kind.accepts(entry)) {
            String advice = kind.advice(entry);
            return new SpineList.Row(entry.name(), advice.isEmpty() ? kind.label().toLowerCase(Locale.ROOT)
                    : advice, false, FITS);
        }
        String what = kind == PathKind.CERTIFICATE && PathKind.PRIVATE_KEY.accepts(entry) ? "a key, not a certificate"
                : kind == PathKind.PRIVATE_KEY && PathKind.CERTIFICATE.accepts(entry) ? "a certificate, not a key"
                : switch (entry.type()) {
                    case LINK -> "link";
                    case OTHER -> "not a file";
                    default -> "file";
                };
        return new SpineList.Row(entry.name(), what, false, OTHER);
    }

    /**
     * A click. Looking for a file, a click on a folder goes in, because a folder cannot be the answer. Looking
     * for a folder, a click picks it.
     */
    private void picked(int index) {
        if (!kind.wantsFolder() && index >= 0 && index < shown.size() && shown.get(index).isDirectory()) {
            open(shown.get(index).path());
            return;
        }
        showPicked();
        RemoteEntry entry = index >= 0 && index < shown.size() ? shown.get(index) : null;
        if (entry != null && peeker != null && kind.readsFirstLine() && kind.accepts(entry)
                && !armours.containsKey(entry.path())) {
            String path = entry.path();
            peeker.peek(path, armour -> {
                armours.put(path, armour == null ? "" : armour);
                showPicked();
            });
        }
    }

    /** What CHOOSE hands back, or null when nothing that fits is picked. */
    private String chosen() {
        int index = list.selected();
        RemoteEntry entry = index >= 0 && index < shown.size() ? shown.get(index) : null;
        if (kind.wantsFolder()) {
            return entry != null && entry.isDirectory() ? entry.path() : current;
        }
        return entry != null && kind.accepts(entry) ? entry.path() : null;
    }

    private void showPicked() {
        int index = list.selected();
        RemoteEntry entry = index >= 0 && index < shown.size() ? shown.get(index) : null;
        String path = chosen();
        boolean fits = entry != null && kind.accepts(entry);
        if (entry == null) {
            picked.setText("NOTHING SELECTED");
        } else if (fits) {
            picked.setText("PICKED  " + entry.name());
        } else {
            picked.setText(entry.name() + " IS NOT A " + kind.label());
        }
        into.setText(path == null ? "" : path);
        choose.setDisable(path == null);
        // The name is already on the left. The button says what it does, and stays whole.
        choose.setText(fits && !kind.wantsFolder() ? "CHOOSE IT"
                : kind.wantsFolder() ? "CHOOSE THIS FOLDER" : "PICK A " + kind.label());

        // The name got it offered; its first line decides whether it can be taken.
        if (fits && peeker != null && kind.readsFirstLine()) {
            String armour = armours.get(entry.path());
            if (armour == null) {
                picked.setText("PICKED  " + entry.name() + "  ·  READING ITS FIRST LINE…");
                choose.setDisable(true);
                return;
            }
            PathKind.Verdict verdict = kind.judge(armour);
            picked.setText(entry.name() + "  ·  " + kind.explain(verdict, armour).toUpperCase(Locale.ROOT));
            picked.getStyleClass().setAll("label", verdict == PathKind.Verdict.FITS ? "card-success"
                    : verdict == PathKind.Verdict.UNKNOWN ? "metric-label" : "card-problem");
            choose.setDisable(verdict == PathKind.Verdict.WRONG || verdict == PathKind.Verdict.LOCKED);
            return;
        }
        picked.getStyleClass().setAll("label", "metric-label");
    }

    /** Only for the previews: what a file's first line said, without a server. */
    public void knowForPreview(String path, String armour) {
        armours.put(path, armour);
        showPicked();
    }

    /** Only for the previews: a folder already read, without a server. */
    public void showForPreview(String path, List<RemoteEntry> entries, int pick) {
        current = path;
        crumbs.show(path);
        folder.setText(Crumbs.name(path));
        fill(entries);
        list.select(pick);
        list.settle();
        showPicked();
        peeker = peeker == null ? (file, done) -> { } : peeker;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
