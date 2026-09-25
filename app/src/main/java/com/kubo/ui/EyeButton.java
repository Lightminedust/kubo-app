package com.kubo.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;

/**
 * The switch that hides or shows what is being typed, drawn as an eye rather than written as a word.
 *
 * <p>SHOW and HIDE next to a text field are ambiguous in the way only two-state words can be: is SHOW what it
 * is doing, or what it will do if pressed? An eye that is open or crossed out says which state you are in and
 * needs no reading at all.
 */
public final class EyeButton extends Button {

    /** An open eye: the almond, and the pupil. */
    private static final String OPEN =
            "M12 5C6.5 5 2.2 8.5 1 12c1.2 3.5 5.5 7 11 7s9.8-3.5 11-7c-1.2-3.5-5.5-7-11-7z"
                    + "M12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z";

    /** The same eye with a stroke through it. */
    private static final String SHUT =
            "M12 5C6.5 5 2.2 8.5 1 12c1.2 3.5 5.5 7 11 7s9.8-3.5 11-7c-1.2-3.5-5.5-7-11-7z"
                    + "M12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"
                    + "M3.2 2.6L21.4 20.8l-1.4 1.4L1.8 4z";

    private static final javafx.scene.paint.Color REST = javafx.scene.paint.Color.web("#a8a8b6");
    private static final javafx.scene.paint.Color OVER = javafx.scene.paint.Color.web("#35e8ff");

    private final SVGPath shape = new SVGPath();
    private boolean hiding;

    public EyeButton() {
        getStyleClass().addAll("ghost-button", "eye-button");
        setFocusTraversable(false);
        shape.setScaleX(0.62);
        shape.setScaleY(0.62);
        // Set here rather than in the stylesheet. A shape's fill defaults to black, and a rule that does not
        // reach it leaves a black eye on a black console: drawn, laid out, and completely invisible.
        shape.setFill(REST);
        hoverProperty().addListener((observable, before, over) -> shape.setFill(over ? OVER : REST));
        setGraphic(shape);
        setHiding(false);
    }

    /** True when what is typed is being hidden. */
    public boolean hiding() {
        return hiding;
    }

    public void setHiding(boolean value) {
        hiding = value;
        shape.setContent(value ? SHUT : OPEN);
        // The tooltip says what pressing it does, which is the half the drawing cannot show.
        setTooltip(new Tooltip(value ? "Show what you are typing" : "Hide what you are typing"));
    }
}
