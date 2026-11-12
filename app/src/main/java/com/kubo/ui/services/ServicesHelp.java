package com.kubo.ui.services;

import com.kubo.model.services.Standing;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/**
 * What the services screen shows, what it changes, what it refuses, and the commands it runs for each.
 *
 * <p>A terminal answers two separate questions: is it running now, and will it come back after a reboot.
 * Nobody asks both about forty services, which is how a server runs for months with something important that
 * will not be there after the next restart. The screen asks both for every unit; this card says how to read it.
 */
final class ServicesHelp extends VBox {

    ServicesHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(14);
        setPadding(new Insets(22, 28, 20, 26));
        setPrefWidth(660);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label("services");
        title.getStyleClass().add("fetch-host");
        Label under = new Label("what systemd keeps running, and what comes back after a reboot");
        under.getStyleClass().add("home-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = ServicesScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, under, spacer, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        GridPane groups = new GridPane();
        groups.setHgap(14);
        groups.setVgap(8);
        int row = 0;
        for (Standing standing : ServicesBoard.ORDER) {
            Rectangle swatch = new Rectangle(9, 9, ServicesBoard.colour(standing));
            HBox mark = new HBox(swatch);
            mark.setAlignment(Pos.CENTER_LEFT);
            mark.setPadding(new Insets(4, 0, 0, 0));
            groups.add(mark, 0, row);
            Label name = new Label(ServicesBoard.word(standing));
            name.getStyleClass().add("fetch-value");
            name.setMinWidth(Region.USE_PREF_SIZE);
            groups.add(name, 1, row);
            groups.add(muted(meaning(standing)), 2, row);
            row++;
        }

        VBox body = new VBox(14,
                section("the groups"), groups,
                muted("a one-shot task, like setting the keyboard, is enabled and idle all its life. kubo asks "
                        + "systemd before calling one a problem, so those stay in idle."),
                section("the tree"),
                muted("a click on a group opens its services. a click on a service grows a column beside it: "
                        + "what holds it up, and what it holds up. a click there grows the next one, and the way "
                        + "back stays on the screen. the wheel or a drag moves the view, the arrow keys walk the "
                        + "list, a double click reads the journal, a right click lists what can be done."),
                section("what can be changed"),
                muted("each line on the right that can be changed has its action at the end: start or stop, "
                        + "turn on or off at boot, a memory or cpu limit without a restart, what systemd does "
                        + "when it dies. signals go to the service directly. all of it needs root."),
                muted("what takes something away asks for the name to be typed back first, and says what "
                        + "systemd lists as depending on it."),
                muted("its own settings go to a file beside the one its package shipped, which is never "
                        + "touched. removing that file puts the service back as the package left it."),
                section("what kubo refuses"),
                muted("stopping or disabling ssh, or the network under it: the server would keep running with "
                        + "no way back in. restarting ssh is allowed, the open sessions survive it."),
                muted("deleting a unit that came with a package. only a unit in /etc/systemd/system, put there "
                        + "by hand, can be deleted, and it is stopped and disabled first."),
                section("what kubo runs"), commands(),
                muted("every command is in the log, never what the server answered. the verb is one of five "
                        + "fixed words and the unit name is quoted, so nothing typed becomes a second command."));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("track-scroll");
        scroll.setPrefViewportHeight(520);

        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        getChildren().addAll(top, scroll);
    }

    private static String meaning(Standing standing) {
        return switch (standing) {
            case FAILED -> "it tried to start and could not, or stopped with an error";
            case WILL_NOT_RETURN -> "running now, but nothing starts it at boot";
            case NOT_RUNNING -> "set to start at boot, and not running now";
            case RUNNING -> "up now, and up again after a reboot";
            case IDLE -> "not running, and nothing asks it to";
        };
    }

    private static GridPane commands() {
        String[] rows = {
            "what runs", "systemctl list-units --type=service --all",
            "what comes back", "systemctl list-unit-files --type=service",
            "the last boot", "systemd-analyze blame  ·  systemd-analyze time",
            "the links", "systemctl show --property=Requires --property=Wants --property=After",
            "one service", "systemctl show <unit>  ·  systemctl cat <unit>",
            "its journal", "journalctl --unit <unit> --lines 200",
            "the verbs", "systemctl start|stop|restart|enable|disable <unit>",
            "a limit", "systemctl set-property <unit> MemoryMax=… CPUQuota=…",
            "a signal", "systemctl kill --signal=… <unit>",
            "its settings", "/etc/systemd/system/<unit>.d/kubo.conf, then daemon-reload",
        };
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        for (int i = 0; i < rows.length; i += 2) {
            Label name = new Label(rows[i]);
            name.getStyleClass().add("fetch-key");
            name.setMinWidth(Region.USE_PREF_SIZE);
            Label command = new Label(rows[i + 1]);
            command.getStyleClass().add("port-quiet");
            command.setWrapText(true);
            grid.add(name, 0, i / 2);
            grid.add(command, 1, i / 2);
        }
        return grid;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("fetch-key");
        label.setPadding(new Insets(4, 0, 0, 0));
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("home-muted");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
