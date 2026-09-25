package com.kubo.ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Lines of a console, each one selectable: click one, shift-click or drag across several, then Ctrl+C or a right
 * click copies exactly those. Nothing else is copied with them.
 *
 * <p>A block that belongs together, a command with the line saying when it ran, is one entry and is selected
 * whole. What a command printed goes in line by line, so one line of an answer can be taken out of it.
 *
 * <p>The view follows new lines only while the reader is at the end and has nothing selected: a console that
 * drags the view down every second, or drops a selection somebody is about to copy, cannot be used.
 */
public final class LogLines extends ListView<LogLines.Line> {

    /**
     * One entry, with the style class it is drawn in and, for a log, where the times are in it.
     *
     * @param spans the pieces of the line and which of them are times, or null for a line drawn all in one
     */
    public record Line(String text, String style, List<com.kubo.model.shell.LogLine.Span> spans) {

        public Line(String text, String style) {
            this(text, style, null);
        }

        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(style, "style");
            spans = spans == null ? null : List.copyOf(spans);
        }
    }

    private final int limit;
    /** Every line there is. What the list shows is a view of this, which is what a filter narrows. */
    private final javafx.collections.ObservableList<Line> all =
            javafx.collections.FXCollections.observableArrayList();
    private final javafx.collections.transformation.FilteredList<Line> shown =
            new javafx.collections.transformation.FilteredList<>(all);
    private IntConsumer onCopied = count -> { };
    private String wanted = "";
    private java.util.Set<String> styles = java.util.Set.of();

    /**
     * @param limit how many entries are kept; the oldest go first
     */
    public LogLines(int limit) {
        this.limit = limit;
        setItems(shown);
        getStyleClass().add("log-lines");
        setCellFactory(view -> new LineCell());
        ListSelect.install(this, count -> onCopied.accept(count),
                new ListSelect.Copy<>("COPY", LogLines::textOf));
    }

    /** Called with how many entries were copied, to say so where the reader is looking. */
    public void setOnCopied(IntConsumer handler) {
        onCopied = handler == null ? count -> { } : handler;
    }

    /** Adds one entry, kept whole however many lines it has. */
    public void append(String text, String style) {
        add(List.of(new Line(text, style)));
    }

    /**
     * Adds one line of a log, drawn in its level's colour with the times inside it dimmed.
     *
     * <p>A timestamp is on almost every line and is almost never what somebody is looking for. Left at the
     * same weight as the message it takes up the left third of the page and the eye reads it every time.
     */
    public void appendLog(com.kubo.model.shell.LogLine line) {
        add(List.of(new Line(line.text(), line.styleClass(), line.spans())));
    }

    /** Adds every line of a text as its own entry, so any one of them can be copied alone. */
    public void appendLines(String text, String style) {
        add(text.lines().map(line -> new Line(line, style)).toList());
    }

    /** Removes every entry. */
    public void clearLines() {
        all.clear();
    }

    /**
     * Shows only the entries holding this text, or everything when it is blank.
     *
     * <p>On what has already arrived, not on the server. Asking a command again through grep means losing
     * everything printed before the question was asked, and a log that has to be restarted to be searched is
     * a log nobody searches.
     */
    public void setFilter(String text) {
        wanted = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        refilter();
    }

    /**
     * Shows only entries drawn in one of these style classes, or everything when the set is empty.
     *
     * <p>Which is how "only what is wrong" works on a log: the colour was decided once, as the line arrived,
     * so narrowing to it costs nothing and never disagrees with what is on the screen.
     */
    public void setStyleFilter(java.util.Set<String> only) {
        styles = only == null ? java.util.Set.of() : java.util.Set.copyOf(only);
        refilter();
    }

    private void refilter() {
        if (wanted.isEmpty() && styles.isEmpty()) {
            shown.setPredicate(null);
            return;
        }
        shown.setPredicate(line -> (styles.isEmpty() || styles.contains(line.style()))
                && (wanted.isEmpty() || line.text().toLowerCase(Locale.ROOT).contains(wanted)));
    }

    /** How many entries there are, whatever the filter is showing. */
    public int total() {
        return all.size();
    }

    /** The text that copying these entries puts on the clipboard: one per line, as shown. */
    public static String textOf(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.text());
        }
        return text.toString();
    }

    private void add(List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        boolean following = getSelectionModel().isEmpty() && atTheEnd();
        all.addAll(lines);
        int over = all.size() - limit;
        if (over > 0) {
            all.remove(0, over);
        }
        if (following) {
            Platform.runLater(() -> scrollTo(Math.max(0, getItems().size() - 1)));
        }
    }

    /**
     * Whether the reader is at the bottom, asked of the scrollbar rather than of the rows.
     *
     * <p>It used to ask the virtual flow which cells it had built, which is only as good as the flow's guess
     * at how tall a cell is. These cells wrap, so their heights differ, and the guess was wrong often enough
     * that a console printing steadily simply stopped following its own output. The scrollbar knows where it
     * is, whatever the rows are doing.
     */
    private boolean atTheEnd() {
        if (getItems().isEmpty()) {
            return true;
        }
        for (javafx.scene.Node node : lookupAll(".scroll-bar")) {
            if (node instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                // Nothing to scroll is the same as being at the end of it.
                return bar.getMax() <= bar.getMin()
                        || bar.getValue() >= bar.getMax() - (bar.getMax() - bar.getMin()) * 0.02;
            }
        }
        return true;
    }

    /**
     * Goes to the last line and stays there, whatever the reader was looking at.
     *
     * <p>For the one case where following is not a guess: somebody pressed Enter, and what happens next is
     * the answer to what they just did.
     */
    public void follow() {
        Platform.runLater(() -> {
            layout();
            scrollTo(Math.max(0, getItems().size() - 1));
        });
    }

    private final class LineCell extends ListCell<Line> {

        private final Label label = new Label();
        private final javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();

        LineCell() {
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.maxWidthProperty().bind(LogLines.this.widthProperty().subtract(34));
            flow.maxWidthProperty().bind(LogLines.this.widthProperty().subtract(34));
            setText(null);
            ListSelect.dragAcross(this);
        }

        @Override
        protected void updateItem(Line line, boolean empty) {
            super.updateItem(line, empty);
            setText(null);
            if (empty || line == null) {
                setGraphic(null);
                return;
            }
            if (line.spans() == null) {
                // An empty line still takes a line's height; what is copied stays empty.
                label.setText(line.text().isEmpty() ? " " : line.text());
                label.getStyleClass().setAll("label", line.style());
                setGraphic(label);
                return;
            }
            flow.getChildren().clear();
            for (com.kubo.model.shell.LogLine.Span span : line.spans()) {
                javafx.scene.text.Text piece = new javafx.scene.text.Text(span.text());
                piece.getStyleClass().setAll("text", span.stamp() ? "log-stamp" : line.style());
                flow.getChildren().add(piece);
            }
            setGraphic(flow);
        }
    }
}
