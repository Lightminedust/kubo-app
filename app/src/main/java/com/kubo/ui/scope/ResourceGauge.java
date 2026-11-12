package com.kubo.ui.scope;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;

import java.util.Locale;

/**
 * One resource: a dial for how full it is, and beside it the few figures that explain the dial, read out
 * the way the machine block reads out the machine.
 *
 * <p>Side by side rather than stacked, so the row of three stays short and the history underneath gets
 * the height. The history is no longer in here: one panel below holds all of it.
 */
final class ResourceGauge extends HBox {

    private static final double RADIUS = 33;
    private static final double WIDTH = 6;

    private final Arc fill = new Arc(0, 0, RADIUS, RADIUS, 90, 0);
    private final Label value = new Label("—");
    private final GridPane facts = new GridPane();
    private final String name;
    private final String colour;

    ResourceGauge(String name, String colour) {
        this.name = name;
        this.colour = colour;
        Color accent = Color.web(colour);
        getStyleClass().add("gauge");
        setSpacing(24);
        setAlignment(Pos.CENTER_LEFT);
        setMinWidth(0);
        setMinHeight(Region.USE_PREF_SIZE);

        Circle track = new Circle(RADIUS, Color.TRANSPARENT);
        track.setStroke(Color.web("#1b1b25"));
        track.setStrokeWidth(WIDTH);
        fill.setType(ArcType.OPEN);
        fill.setFill(Color.TRANSPARENT);
        fill.setStroke(accent);
        fill.setStrokeWidth(WIDTH);
        fill.setStrokeLineCap(StrokeLineCap.BUTT);
        // The faint halo every lit thing on this screen has, so the dial reads as lit rather than printed.
        fill.setEffect(new DropShadow(BlurType.GAUSSIAN, accent.deriveColor(0, 1, 1, 0.5), 10, 0.1, 0, 0));
        Group ring = new Group(track, fill);
        value.getStyleClass().add("gauge-value");
        StackPane dial = new StackPane(ring, value);
        double size = (RADIUS + WIDTH) * 2 + 4;
        dial.setMinSize(size, size);
        dial.setPrefSize(size, size);
        dial.setMaxSize(size, size);

        facts.setHgap(14);
        facts.setVgap(5);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setMinWidth(0);
        facts.getColumnConstraints().addAll(keys, values);
        facts.setMinWidth(0);
        HBox.setHgrow(facts, Priority.ALWAYS);

        getChildren().addAll(dial, facts);
        setAccessibleText(name + ": waiting for data");
    }

    void update(double percent) {
        boolean known = Double.isFinite(percent);
        double bounded = known ? Math.max(0, Math.min(100, percent)) : 0;
        fill.setLength(-360 * bounded / 100);
        value.setText(known ? String.format(Locale.ROOT, "%.0f%%", bounded) : "—");
        setAccessibleText(name + ": " + value.getText());
    }

    /** The figures beside the dial, as key and value, in that order. */
    void facts(String... pairs) {
        String[][] rows = new String[pairs.length / 2][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new String[]{pairs[i * 2], pairs[i * 2 + 1], pairs[i * 2] + "  " + pairs[i * 2 + 1]};
        }
        rows(rows);
    }

    /** The same, with what to say on hover for each line when the line itself is too short to say it. */
    void rows(String[]... rows) {
        facts.getChildren().clear();
        for (int i = 0; i < rows.length; i++) {
            Label key = new Label(rows[i][0]);
            key.getStyleClass().add("gauge-key");
            key.setStyle("-fx-text-fill: " + colour + ";");
            key.setMinWidth(Region.USE_PREF_SIZE);
            Label fact = new Label(rows[i][1]);
            fact.getStyleClass().add("gauge-fact");
            fact.setMinWidth(Region.USE_PREF_SIZE);
            Tooltip tip = new Tooltip(rows[i][2]);
            key.setTooltip(tip);
            fact.setTooltip(tip);
            facts.add(key, 0, i);
            facts.add(fact, 1, i);
        }
    }
}
