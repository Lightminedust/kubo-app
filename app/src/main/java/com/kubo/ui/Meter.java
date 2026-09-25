package com.kubo.ui;

import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * A share of something, drawn as a bar rather than written as a number.
 *
 * <p>A column of percentages is read line by line. The same column as bars is read at a glance, because the
 * eye compares lengths without being asked to. Both are here: the number stays next to it, because a bar says
 * which is biggest and a number says how big.
 */
public final class Meter extends StackPane {

    private final Region fill = new Region();
    private double share;

    /**
     * @param width how wide the track is
     * @param height how tall
     */
    public Meter(double width, double height) {
        getStyleClass().add("meter");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);
        setAlignment(Pos.CENTER_LEFT);
        fill.getStyleClass().add("meter-fill");
        fill.setMinHeight(height);
        fill.setPrefHeight(height);
        fill.setMaxHeight(height);
        getChildren().add(fill);
        setShare(0);
    }

    /**
     * @param value 0 to 1; anything outside is brought back in
     */
    public void setShare(double value) {
        share = Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        double width = getPrefWidth() * share;
        // A share that is small but not nothing still gets a mark, so "barely running" and "not running" do
        // not look the same.
        fill.setPrefWidth(share > 0 ? Math.max(2, width) : 0);
        fill.setMinWidth(fill.getPrefWidth());
        fill.setMaxWidth(fill.getPrefWidth());
        fill.getStyleClass().removeAll("meter-fill-warm", "meter-fill-hot");
        if (share >= 0.8) {
            fill.getStyleClass().add("meter-fill-hot");
        } else if (share >= 0.5) {
            fill.getStyleClass().add("meter-fill-warm");
        }
    }

    public double share() {
        return share;
    }
}
