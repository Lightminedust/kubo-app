package com.kubo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * A path from one thing to the next, drawn down a spine: a domain, the file that answers it, the rule, the port,
 * the program. Each link says what it is and whether it holds.
 *
 * <p>The spine turns red below a link that is broken, because that is where a request stops. A proxy to a port
 * nothing listens on is a 502 somebody will report as "the site is down", and on this it is a red ring with the
 * reason beside it.
 *
 * <p>It knows nothing about Nginx. It is given links, and a link that has somewhere to go is clickable.
 */
public final class Chain extends VBox {

    /** Whether a link holds. */
    public enum State {
        /** Checked, and it holds. */
        WORKS(Color.web("#35e0a1")),
        /** Checked, and it does not: a request stops here. */
        BROKEN(Color.web("#ff2d55")),
        /** Worth a warning without being broken, such as a certificate close to its end. */
        WARNS(Color.web("#e8c35a")),
        /** Not something Kubo can check from here. Said as such, not painted green. */
        UNKNOWN(Color.web("#6e6e7d"));

        private final Color color;

        State(Color color) {
            this.color = color;
        }

        public Color color() {
            return color;
        }
    }

    /**
     * One link.
     *
     * @param kind  what it is, in capitals: THE DOMAIN, THE PORT
     * @param value the thing itself: gemmi-e.com, 3000
     * @param note  what is true about it, in a sentence, or empty
     * @param open  what a click does, or null for a link that goes nowhere
     */
    public record Link(String kind, String value, String note, State state, Runnable open) {

        public Link {
            kind = kind == null ? "" : kind;
            value = value == null ? "" : value;
            note = note == null ? "" : note;
            state = state == null ? State.UNKNOWN : state;
        }
    }

    private static final Color SPINE = Color.web("#9d4dff");

    public Chain() {
        setSpacing(0);
        getStyleClass().add("chain");
    }

    public void setLinks(List<Link> links) {
        getChildren().clear();
        if (links == null) {
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            boolean first = i == 0;
            boolean last = i == links.size() - 1;
            // The segment above a link is red when this link is where things stop.
            Color above = first ? null : link.state() == State.BROKEN ? State.BROKEN.color() : SPINE;
            Color below = last ? null : links.get(i + 1).state() == State.BROKEN ? State.BROKEN.color() : SPINE;
            getChildren().add(row(link, above, below));
        }
    }

    private HBox row(Link link, Color above, Color below) {
        Marker marker = new Marker(link.state().color(), above, below);

        Label kind = new Label(link.kind());
        kind.getStyleClass().add("metric-label");
        Label value = new Label(link.value());
        value.getStyleClass().add(link.state() == State.BROKEN ? "chain-value-broken" : "mono");
        value.setWrapText(true);
        VBox text = new VBox(1, kind, value);
        if (!link.note().isEmpty()) {
            Label note = new Label(link.note());
            note.getStyleClass().add(link.state() == State.BROKEN ? "card-problem" : "card-note");
            note.setWrapText(true);
            note.setMinHeight(Region.USE_PREF_SIZE);
            text.getChildren().add(note);
        }
        text.setPadding(new Insets(6, 0, 8, 0));
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, marker, text);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("chain-row");
        if (link.open() != null) {
            Label go = new Label("›");
            go.getStyleClass().add("mono-muted");
            go.setPadding(new Insets(14, 6, 0, 0));
            row.getChildren().add(go);
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(event -> link.open().run());
            row.getStyleClass().add("chain-row-open");
        }
        marker.prefHeightProperty().bind(row.heightProperty());
        return row;
    }

    /** The spine through one row: the segment in, the ring, the segment out. */
    private static final class Marker extends Region {

        private final Canvas canvas = new Canvas(28, 10);
        private final Color ring;
        private final Color above;
        private final Color below;

        Marker(Color ring, Color above, Color below) {
            this.ring = ring;
            this.above = above;
            this.below = below;
            setMinWidth(28);
            setPrefWidth(28);
            setMaxWidth(28);
            getChildren().add(canvas);
            heightProperty().addListener((observable, before, now) -> draw());
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        private void draw() {
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            if (width < 1 || height < 1) {
                return;
            }
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.clearRect(0, 0, width, height);
            double x = 14.5;
            double y = 20;
            gc.setLineWidth(1.2);
            if (above != null) {
                gc.setGlobalAlpha(above == State.BROKEN.color() ? 0.9 : 0.4);
                gc.setStroke(above);
                gc.strokeLine(x, 0, x, y - 7);
            }
            if (below != null) {
                gc.setGlobalAlpha(below == State.BROKEN.color() ? 0.9 : 0.4);
                gc.setStroke(below);
                gc.strokeLine(x, y + 7, x, height);
            }
            gc.setGlobalAlpha(1);
            gc.setStroke(ring);
            gc.setLineWidth(1.6);
            gc.strokeOval(x - 5, y - 5, 10, 10);
            gc.setGlobalAlpha(0.35);
            gc.setFill(ring);
            gc.fillOval(x - 2.5, y - 2.5, 5, 5);
            gc.setGlobalAlpha(1);
        }
    }
}
