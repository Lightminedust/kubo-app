package com.kubo.ui.network;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * A card explaining the five lanes, what each colour means, and exactly which command every action runs.
 */
final class NetworkHelp extends VBox {

    NetworkHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("READING THE NETWORK SCREEN", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label lanes = label("READ IT LEFT TO RIGHT, THE WAY A REQUEST ARRIVES: THE INTERNET ON THE LEFT, THE "
                + "FIREWALL AS A PANE OF GLASS ACROSS THE PATH, THEN THE PORTS GROUPED BY HOW FAR THEY CAN REALLY "
                + "BE REACHED, AND THIS SERVER ON THE RIGHT. A FILAMENT RUNNING THE WHOLE WIDTH MEANS THE INTERNET "
                + "REALLY CAN REACH THAT PROCESS. CLICK A BRANCH TO FOLD IT, A PORT TO LIGHT ONLY WHAT RELATES "
                + "TO IT.", "card-note");
        lanes.setWrapText(true);
        lanes.setMaxWidth(540);

        GridPane marks = markTable();

        GridPane colours = colourTable(
                Color.web("#ff2d55"), "EXPOSED \u2014 a database or admin port the internet can reach. Act on this.",
                Color.web("#ffb347"), "OPEN \u2014 reachable from the internet. Normal for 80 and 443.",
                Color.web("#35e8ff"), "BEHIND THE FIREWALL \u2014 it binds every interface, but the firewall stops "
                        + "the internet.",
                Color.web("#8a4dff"), "PRIVATE NETWORK \u2014 a Docker bridge or a provider's internal network.",
                Color.web("#35e0a1"), "LOOPBACK ONLY \u2014 nothing outside the server can open it. The safe case.");

        GridPane actions = actionTable(
                "OPEN PORT", "ufw allow <port>/<proto>",
                "CLOSE PORT", "ufw --force delete allow <port>/<proto>",
                "RESTART APP", "pm2 restart <app>",
                "STOP APP", "pm2 stop <app>",
                "APP LOGS", "pm2 logs <app> --lines 200 --nostream",
                "KILL PID", "kill -9 <pid>",
                "TEST & RELOAD", "nginx -t && systemctl reload nginx");

        Label writing = label("KUBO READS UFW, FIREWALLD, NFTABLES AND IPTABLES, BUT IT ONLY WRITES UFW RULES. ON "
                + "ANY OTHER FIREWALL THE OPEN AND CLOSE BUTTONS ARE NOT OFFERED AT ALL, RATHER THAN RUNNING THE "
                + "WRONG TOOL.", "card-note");
        writing.setWrapText(true);
        writing.setMaxWidth(540);

        Label root = label("THE FIREWALL RULES AND THE NGINX CONFIGURATION ONLY ANSWER TO ROOT, SO TURN ON ROOT IN "
                + "THE HEADER: KUBO ASKS FOR YOUR SUDO PASSWORD ONCE AND KEEPS IT IN MEMORY FOR THE SESSION ONLY. "
                + "ANYTHING THAT CANNOT BE UNDONE \u2014 CLOSING A PORT, KILLING A PROCESS \u2014 MAKES YOU TYPE "
                + "THE PORT OR PID BACK FIRST.", "card-note");
        root.setWrapText(true);
        root.setMaxWidth(540);

        Label read = label("KUBO NEVER INSTALLS ANYTHING. IT READS ss, pm2 jlist, THE FIREWALL'S OWN STATUS AND "
                + "nginx -T, THEN MATCHES THE PIDS AGAINST PM2 AND THE PUBLISHED PORTS AGAINST DOCKER TO NAME WHAT "
                + "HOLDS EACH PORT. /usr/sbin AND /sbin ARE PUT BACK ON THE PATH FIRST, SINCE AN SSH SESSION DROPS "
                + "THEM AND EVERY ONE OF THOSE TOOLS LIVES THERE. EVERY COMMAND IT SENDS IS LISTED IN THE LOGS.",
                "card-note");
        read.setWrapText(true);
        read.setMaxWidth(540);

        VBox body = new VBox(14, label("HOW TO READ IT", "series"), lanes,
                label("THE MARKS ON THE MAP", "series"), marks,
                label("WHAT THE COLOURS MEAN", "series"), colours,
                label("WHAT EACH ACTION RUNS", "series"), actions, writing, root, read);

        // The card is taller than the window it sits in, so the explanation scrolls rather than being cut off.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(560);
        scroll.setMaxWidth(600);

        getChildren().addAll(header, scroll);
    }

    /**
     * The marks, drawn the way the map draws them rather than described in words. The cross is the one people ask
     * about: it is where a branch meets the firewall and stops.
     */
    private static GridPane markTable() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);
        Object[][] rows = {
            {pane(Color.web("#35e8ff"), Pane.WHOLE), "THE FIREWALL",
                "A pane of glass across the path. Lit and whole while it is enforcing."},
            {pane(Color.web("#35e8ff"), Pane.STOPPED), "A CROSS ON THE PANE",
                "This branch's traffic arrives at the firewall and goes no further \u2014 the ports behind it bind "
                + "every interface, but the internet cannot open them. The faint line bouncing back is that "
                + "refusal."},
            {pane(Color.web("#ff2d55"), Pane.BROKEN), "A BROKEN PANE",
                "Nothing is filtered: the firewall is off, or there is none. Every listening port is reachable."},
            {pane(Color.web("#ffb347"), Pane.FOGGED), "A FOGGED PANE",
                "The firewall is there but Kubo cannot see its rules — they need root (turn on ROOT and "
                + "refresh), or they are in a form Kubo does not read. Nothing here counts it as protection, "
                + "but it may well be protecting you."},
        };
        for (int i = 0; i < rows.length; i++) {
            grid.add((javafx.scene.Node) rows[i][0], 0, i);
            Label name = label((String) rows[i][1], "mono");
            name.setMinWidth(150);
            grid.add(name, 1, i);
            Label meaning = label((String) rows[i][2], "card-note");
            meaning.setWrapText(true);
            meaning.setMaxWidth(320);
            grid.add(meaning, 2, i);
        }
        return grid;
    }

    private enum Pane { WHOLE, STOPPED, BROKEN, FOGGED }

    /**
     * A small drawing of the firewall pane in one of its states.
     */
    private static javafx.scene.canvas.Canvas pane(Color colour, Pane state) {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(54, 46);
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        double x = 27;
        double half = 7;
        double top = 4;
        double bottom = 42;

        if (state == Pane.BROKEN) {
            gc.setStroke(colour);
            gc.setLineWidth(1.2);
            gc.setGlobalAlpha(0.6);
            for (int i = 0; i < 6; i++) {
                double y = top + i * (bottom - top) / 6 + 2;
                gc.strokeLine(x - half, y, x + half + (i % 2 == 0 ? 5 : -4), y + 2);
            }
            gc.setGlobalAlpha(1);
            return canvas;
        }
        if (state == Pane.FOGGED) {
            gc.setFill(colour);
            for (int i = 0; i < 40; i++) {
                double y = top + (i * 37 % 100) / 100.0 * (bottom - top);
                double dx = ((i * 53 % 100) / 100.0 - 0.5) * half * 2;
                gc.setGlobalAlpha(0.22);
                gc.fillOval(x + dx - 1, y - 1, 2, 2);
            }
            gc.setGlobalAlpha(1);
            return canvas;
        }

        gc.setFill(new javafx.scene.paint.LinearGradient(x - half, 0, x + half, 0, false,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, colour.deriveColor(0, 1, 1, 0)),
                new javafx.scene.paint.Stop(0.5, colour.deriveColor(0, 1, 1, 0.3)),
                new javafx.scene.paint.Stop(1, colour.deriveColor(0, 1, 1, 0))));
        gc.fillRect(x - half, top, half * 2, bottom - top);
        gc.setStroke(colour.deriveColor(0, 1, 1, 0.85));
        gc.setLineWidth(1.4);
        gc.strokeLine(x - half, top, x - half, bottom);
        gc.setStroke(colour.deriveColor(0, 1, 1, 0.5));
        gc.strokeLine(x + half, top, x + half, bottom);

        if (state == Pane.STOPPED) {
            // The filament arriving, and the cross where it is refused.
            gc.setStroke(colour.deriveColor(0, 1, 1, 0.5));
            gc.setLineWidth(1);
            gc.strokeLine(0, 30, x - half, 23);
            gc.setStroke(colour.deriveColor(0, 1, 1, 0.22));
            gc.strokeLine(x - half, 23, 8, 15);
            gc.setStroke(colour);
            gc.setLineWidth(2);
            gc.strokeLine(x - half - 5, 18, x - half + 5, 28);
            gc.strokeLine(x - half - 5, 28, x - half + 5, 18);
        }
        return canvas;
    }

    private static GridPane colourTable(Object... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(7);
        for (int i = 0; i < rows.length; i += 2) {
            HBox mark = new HBox(new Circle(5, (Color) rows[i]));
            mark.setAlignment(Pos.CENTER_LEFT);
            mark.setMinWidth(30);
            grid.add(mark, 0, i / 2);
            Label text = label((String) rows[i + 1], "card-note");
            text.setWrapText(true);
            text.setMaxWidth(500);
            grid.add(text, 1, i / 2);
        }
        return grid;
    }

    private static GridPane actionTable(String... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(6);
        grid.add(label("BUTTON", "metric-label"), 0, 0);
        grid.add(label("RUNS", "metric-label"), 1, 0);
        for (int i = 0; i < rows.length; i += 2) {
            Label name = label(rows[i], "redis-row-name");
            name.setMinWidth(130);
            grid.add(name, 0, i / 2 + 1);
            grid.add(label(rows[i + 1], "mono-muted"), 1, i / 2 + 1);
        }
        return grid;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
