package com.kubo.ui;

import javafx.application.Platform;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.Region;

import java.util.List;
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

    /** One entry, with the style class it is drawn in. */
    public record Line(String text, String style) {

        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(style, "style");
        }
    }

    private final int limit;
    private IntConsumer onCopied = count -> { };

    /**
     * @param limit how many entries are kept; the oldest go first
     */
    public LogLines(int limit) {
        this.limit = limit;
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

    /** Adds every line of a text as its own entry, so any one of them can be copied alone. */
    public void appendLines(String text, String style) {
        add(text.lines().map(line -> new Line(line, style)).toList());
    }

    /** Removes every entry. */
    public void clearLines() {
        getItems().clear();
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
        getItems().addAll(lines);
        int over = getItems().size() - limit;
        if (over > 0) {
            getItems().remove(0, over);
        }
        if (following) {
            Platform.runLater(() -> scrollTo(Math.max(0, getItems().size() - 1)));
        }
    }

    /**
     * Whether the last entry is on screen. A list view does not say where it is scrolled, only which rows it
     * built, so that is what is asked.
     */
    private boolean atTheEnd() {
        if (getItems().isEmpty()) {
            return true;
        }
        if (!(lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return true;
        }
        IndexedCell<?> last = flow.getLastVisibleCell();
        return last == null || last.getIndex() >= getItems().size() - 2;
    }

    private final class LineCell extends ListCell<Line> {

        private final Label label = new Label();

        LineCell() {
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.maxWidthProperty().bind(LogLines.this.widthProperty().subtract(34));
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
            // An empty line still takes a line's height; what is copied stays empty.
            label.setText(line.text().isEmpty() ? " " : line.text());
            label.getStyleClass().setAll("label", line.style());
            setGraphic(label);
        }
    }
}
