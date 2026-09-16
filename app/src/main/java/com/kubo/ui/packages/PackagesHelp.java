package com.kubo.ui.packages;

import com.kubo.model.packages.PackageRole;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

/**
 * What everything on the packages screen means: the six branches, and the marks a dot wears.
 *
 * <p>The marks are drawn here the same way the tree draws them, from the same palette, rather than described in
 * words — a legend that only says "an amber arc" is one more thing to decode.
 */
final class PackagesHelp extends VBox {

    private static final double WIDTH = 560;

    PackagesHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Label title = label("READING THE PACKAGES TREE", "card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(14,
                label("NAVIGATION", "series"),
                note("THE SERVER IS THE ROOT. IT BRANCHES INTO WHAT PACKAGES ARE FOR, A BRANCH OPENS INTO ITS DPKG "
                        + "SECTIONS, AND A SECTION INTO ITS PACKAGES. CLICK A ROW TO OPEN IT, CLICK IT AGAIN TO "
                        + "CLOSE IT. DRAG TO PAN WITH INERTIA, CTRL+WHEEL TO ZOOM, FIT TO FRAME. CLICKING A "
                        + "PACKAGE SELECTS IT IN THE LIST ON THE RIGHT."),

                label("THE SIX BRANCHES", "series"),
                branches(),

                label("WHAT A DOT SAYS", "series"),
                marks(),

                label("IN THE LIST ON THE RIGHT", "series"),
                listMarks(),

                label("THE ACTIONS", "series"),
                actions(),
                note("EVERY CHANGE IS ASKED OF APT AS A SIMULATION FIRST, SHOWN TO YOU, AND CONFIRMED BY TYPING THE "
                        + "NAME BACK. NOTHING RUNS AS ROOT UNTIL YOU TURN ROOT ON."));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(560);
        scroll.setMaxWidth(WIDTH + 30);

        getChildren().addAll(header, scroll);
    }

    /**
     * One row per role, with its own colour and the plain reason it exists.
     */
    private static GridPane branches() {
        GridPane grid = grid();
        int row = 0;
        for (PackageRole role : PackageRole.values()) {
            grid.add(swatch(PackagePalette.of(role)), 0, row);
            Label name = label(role.title(), "mono");
            name.setMinWidth(112);
            grid.add(name, 1, row);
            grid.add(note(role.note().toUpperCase(java.util.Locale.ROOT)), 2, row);
            row++;
        }
        return grid;
    }

    private static GridPane marks() {
        GridPane grid = grid();
        add(grid, 0, dot(1, -1, -1, false),
                "HOW BIG THE DOT IS", "How many packages the branch holds — for one package, how much disk it takes.");
        add(grid, 1, dot(1, 0.62, -1, false),
                "THE RING ON IT", "The share someone asked for. A full ring means nothing there came in only as a "
                        + "dependency; a short arc means almost all of it did.");
        add(grid, 2, dot(1, 0.4, 0.45, false),
                "AN AMBER ARC OUTSIDE", "Packages waiting for a new version. The arc is how much of the branch is "
                        + "waiting.");
        add(grid, 3, dot(1, 0.4, 0.6, true),
                "A RED ARC OUTSIDE", "At least one of those waiting updates is a security fix.");
        return grid;
    }

    private static GridPane listMarks() {
        GridPane grid = grid();
        add(grid, 0, swatch(PackagePalette.ALERT), "RED", "A security update is waiting for this package.");
        add(grid, 1, swatch(PackagePalette.AMBER), "AMBER", "An ordinary update is waiting.");
        add(grid, 2, swatch(PackagePalette.QUIET), "GREY", "It only came in as a dependency, or nothing needs it any "
                + "more (NO LONGER NEEDED).");
        add(grid, 3, swatch(PackagePalette.LIVE), "GREEN", "Up to date, and asked for by someone rather than pulled "
                + "in.");
        return grid;
    }

    private static GridPane actions() {
        GridPane grid = grid();
        String[] rows = {
            "UPDATE INDEX", "Re-reads what versions exist. Changes nothing installed (apt-get update).",
            "UPGRADE ALL", "Installs every waiting version without adding or removing anything (apt-get upgrade).",
            "FULL UPGRADE", "The same, but may add and remove packages to satisfy new dependencies.",
            "AUTOREMOVE", "Removes what nothing depends on any more.",
            "CLEAN CACHE", "Frees the downloaded .deb archives. Nothing installed is touched.",
            "ONLY WHAT WAS ASKED FOR", "Hides, everywhere, the packages that only arrived as dependencies.",
            "SHOW EVERYTHING", "Closes the tree back to the six branches.",
        };
        for (int i = 0; i < rows.length; i += 2) {
            int row = i / 2;
            Label name = label(rows[i], "mono");
            name.setMinWidth(112);
            grid.add(name, 1, row);
            grid.add(note(rows[i + 1]), 2, row);
        }
        return grid;
    }

    private static void add(GridPane grid, int row, Node mark, String name, String meaning) {
        grid.add(mark, 0, row);
        Label label = label(name, "mono");
        label.setMinWidth(112);
        grid.add(label, 1, row);
        grid.add(note(meaning), 2, row);
    }

    /**
     * Draws a dot exactly as the tree does: the disc, the ring for the share asked for, the arc for what is waiting.
     *
     * @param asked   share of the inner ring, or -1 for none
     * @param waiting share of the outer arc, or -1 for none
     */
    private static Canvas dot(double size, double asked, double waiting, boolean security) {
        Canvas canvas = new Canvas(46, 30);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        Color colour = PackagePalette.of(PackageRole.SERVICES);
        double x = 23;
        double y = 15;
        double r = 5.5 * size;

        gc.setFill(colour.deriveColor(0, 1, 1, 0.20));
        gc.fillOval(x - r * 2, y - r * 2, r * 4, r * 4);
        gc.setFill(colour);
        gc.fillOval(x - r, y - r, r * 2, r * 2);
        if (asked > 0) {
            double ring = r + 3;
            gc.setStroke(colour);
            gc.setLineWidth(1.8);
            gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, 90, -360 * asked, ArcType.OPEN);
        }
        if (waiting > 0) {
            double ring = r + 7;
            gc.setStroke(security ? PackagePalette.ALERT : PackagePalette.AMBER);
            gc.setLineWidth(1.8);
            gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, 90, -360 * waiting, ArcType.OPEN);
        }
        return canvas;
    }

    private static Node swatch(Color colour) {
        Canvas canvas = new Canvas(46, 30);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(colour.deriveColor(0, 1, 1, 0.22));
        gc.fillOval(12, 4, 22, 22);
        gc.setFill(colour);
        gc.fillOval(18, 10, 10, 10);
        return new HBox(canvas);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(9);
        return grid;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(WIDTH - 200);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
