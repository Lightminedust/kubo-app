package com.kubo.ui.services;

import com.kubo.model.services.Standing;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * A card explaining the five banks, the two bands, and exactly which command the screen runs.
 *
 * <p>It says the thing a terminal makes you work out for yourself: {@code systemctl status} tells you whether
 * a service is running, and {@code systemctl is-enabled} tells you whether it will come back. Nobody runs both
 * for forty services, which is why a server can sit for months with something important that will vanish at
 * the next reboot.
 */
final class ServicesHelp extends VBox {

    ServicesHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("\u2715");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("READING THE SERVICES SCREEN", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label what = note("A SERVICE IS A PROGRAM THE MACHINE KEEPS RUNNING FOR YOU: YOUR WEB SERVER, YOUR "
                + "DATABASE, THE THING THAT RENEWS YOUR CERTIFICATES. SYSTEMD STARTS THEM, RESTARTS THEM WHEN "
                + "THEY DIE, AND BRINGS THEM BACK AFTER A REBOOT \u2014 OR DOES NOT, WHICH IS THE WHOLE POINT "
                + "OF THIS SCREEN.");

        Label two = note("A TERMINAL ANSWERS TWO SEPARATE QUESTIONS: IS IT RUNNING NOW, AND WILL IT COME BACK "
                + "AFTER A REBOOT. NOBODY ASKS BOTH ABOUT FORTY SERVICES, SO A SERVER CAN RUN FOR MONTHS WITH "
                + "SOMETHING IMPORTANT THAT WILL SIMPLY NOT BE THERE AFTER THE NEXT RESTART. KUBO ASKS BOTH, "
                + "FOR EVERY UNIT, AND PUTS EACH ONE IN THE GROUP ITS TWO ANSWERS PUT IT IN.");

        Label layout = note("A TREE THAT KEEPS GOING, AND THE DETAIL OF WHATEVER YOU CLICKED BESIDE IT. "
                + "THE FIRST COLUMN IS THE FIVE STANDINGS, EACH ITS OWN COLOUR. THE SECOND IS THE SERVICES OF "
                + "WHICHEVER ONE IS OPEN.");

        Label growing = note("CLICK A SERVICE AND A THIRD COLUMN APPEARS BESIDE IT: WHAT HOLDS IT UP, THEN "
                + "WHAT IT HOLDS UP. CLICK ONE OF THOSE AND A FOURTH APPEARS. NOTHING IS TAKEN AWAY AND "
                + "NOTHING JUMPS \u2014 THE WAY YOU CAME IS STILL THERE BEHIND YOU, WHICH IS THE ONE THING A "
                + "PICTURE OF A DEPENDENCY GRAPH HAS TO DO IF ANYBODY IS TO FIND THEIR WAY IN IT.");

        Label why = note("THAT IS ALSO WHY NO LINK IS DRAWN ACROSS THE TREE. A SERVER HAS TWO HUNDRED UNITS, "
                + "AND TWO HUNDRED THINGS WITH THEIR LINKS ON ONE PICTURE IS A THICKET NOBODY CAN READ, "
                + "WHATEVER SHAPE IT IS GIVEN. A COLUMN AT A TIME, THERE ARE NEVER MORE THAN A HANDFUL.");

        Label again = note("A SERVICE ALREADY SHOWN FURTHER LEFT MAY APPEAR AGAIN FURTHER RIGHT. THAT IS NOT "
                + "A FAULT: IT IS WHAT LETS THE TREE KEEP GOING WITHOUT EVER FOLDING BACK ON ITSELF. AND A "
                + "NEIGHBOUR KEEPS THE COLOUR OF ITS OWN STANDING, SO A DEAD DEPENDENCY SHOWS ITSELF AT ONCE.");

        Label moving = note("IT MOVES THE WAY THE FILE TREE MOVES, BECAUSE IT IS THE SAME CAMERA: THE WHEEL "
                + "TRAVELS, CONTROL AND THE WHEEL ZOOM, AND A DRAG IS A DRAG. A LONG LIST IS SCROLLED RATHER "
                + "THAN SHRUNK TO FIT \u2014 A HUNDRED SERVICES MADE TO FIT A WINDOW IS A HUNDRED SERVICES "
                + "NOBODY CAN READ.");

        GridPane banks = new GridPane();
        banks.setHgap(14);
        banks.setVgap(9);
        int row = 0;
        for (Standing standing : Standing.values()) {
            HBox mark = new HBox(swatch(ServicesTree.colour(standing)));
            mark.setAlignment(Pos.CENTER_LEFT);
            mark.setMinWidth(26);
            banks.add(mark, 0, row);
            Label name = label(standing.label(), "mono");
            name.setMinWidth(180);
            banks.add(name, 1, row);
            Label meaning = label(standing.meaning().toUpperCase(java.util.Locale.ROOT), "card-note");
            meaning.setWrapText(true);
            meaning.setMaxWidth(340);
            banks.add(meaning, 2, row);
            row++;
        }

        Label falseAlarm = note("ONE OF THESE IS EASY TO GET WRONG, SO KUBO ASKS SYSTEMD BEFORE CALLING IT A "
                + "PROBLEM: A ONE-SHOT TASK \u2014 SETTING THE KEYBOARD, CLEANING A DIRECTORY \u2014 IS "
                + "ENABLED AND IDLE FOR THE WHOLE LIFE OF THE MACHINE, WHICH LOOKS EXACTLY LIKE A SERVICE THAT "
                + "SHOULD BE UP AND IS NOT. THOSE STAY IN IDLE.");

        Label boot = note("A SERVICE THAT HELD UP THE LAST BOOT FOR MORE THAN A SECOND CARRIES THAT TIME "
                + "BESIDE ITS NAME. THE TOTAL FOR THE WHOLE MACHINE IS IN THE HEADER, ABOVE ON THE RIGHT.");

        GridPane reads = new GridPane();
        reads.setHgap(18);
        reads.setVgap(6);
        reads.add(label("TO FIND OUT", "metric-label"), 0, 0);
        reads.add(label("KUBO RUNS", "metric-label"), 1, 0);
        String[] rows = {
            "WHAT IS LOADED NOW", "systemctl list-units --type=service --all",
            "WHAT COMES BACK AT BOOT", "systemctl list-unit-files --type=service",
            "WHAT THE LAST BOOT COST", "systemd-analyze blame  /  systemd-analyze time",
            "IS IT A ONE-SHOT TASK", "systemctl show --property=Type",
            "WHAT DEPENDS ON WHAT", "systemctl show --property=Requires --property=Wants --property=After",
            "THE DETAIL OF ONE UNIT", "systemctl show <unit>  /  systemctl cat <unit>",
            "WHAT IT LAST WROTE", "journalctl --unit <unit> --lines 200",
            "EACH OF THE FIVE ACTIONS", "systemctl start|stop|restart|enable|disable <unit>",
            "A LIMIT, WITHOUT A RESTART", "systemctl set-property <unit> MemoryMax=... | CPUQuota=...",
            "A SIGNAL", "systemctl kill --signal=... --kill-who=main|all <unit>",
            "ITS OWN SETTINGS", "/etc/systemd/system/<unit>.d/kubo.conf, then systemctl daemon-reload",
            "TAKING ONE OFF FOR GOOD", "stop, disable, rm /etc/systemd/system/<unit>, then daemon-reload",
        };
        for (int i = 0; i < rows.length; i += 2) {
            Label name = label(rows[i], "redis-row-name");
            name.setMinWidth(200);
            reads.add(name, 0, i / 2 + 1);
            reads.add(label(rows[i + 1], "mono-muted"), 1, i / 2 + 1);
        }

        Label acting = note("THE SAME CARD CARRIES THE FIVE THINGS YOU CAN DO: START, STOP, RESTART, "
                + "ENABLE, DISABLE. A BUTTON IS ONLY THERE WHEN IT WOULD CHANGE SOMETHING \u2014 NO START ON A "
                + "SERVICE THAT IS ALREADY UP. THE THREE THAT TAKE SOMETHING AWAY MAKE YOU TYPE THE NAME BACK "
                + "FIRST, AND TELL YOU WHAT SYSTEMD SAYS DEPENDS ON IT. ALL FIVE NEED ROOT.");

        Label holding = note("YOU CAN ALSO HOLD A SERVICE BACK WITHOUT STOPPING IT: A MEMORY CEILING OR A "
                + "SHARE OF THE PROCESSOR. SYSTEMD APPLIES BOTH AT ONCE AND KEEPS THEM ACROSS A RESTART. "
                + "THAT IS THE ANSWER TO A SERVICE EATING THE MACHINE WHEN YOU NEED IT ALIVE LONG ENOUGH TO "
                + "LOOK AT IT. SET EITHER TO INFINITY TO TAKE THE LIMIT OFF.");

        Label signals = note("AND YOU CAN SEND IT A SIGNAL DIRECTLY, WITHOUT THE STOP THAT USUALLY CARRIES "
                + "ONE: SIGHUP TO MAKE IT RE-READ ITS CONFIGURATION, SIGUSR1 AND SIGUSR2 FOR WHATEVER THAT "
                + "SERVICE CHOSE THEM TO MEAN, SIGTERM AND SIGKILL TO END IT. NONE OF THEM CHANGES WHETHER "
                + "SYSTEMD CONSIDERS THE SERVICE RUNNING.");

        Label settings = note("THE UNIT FILE YOUR PACKAGE SHIPPED IS NEVER TOUCHED. WHAT YOU WRITE UNDER ITS "
                + "OWN SETTINGS GOES INTO A FILE OF ITS OWN IN /ETC/SYSTEMD/SYSTEM, WHICH SYSTEMD READS "
                + "AFTER IT, SO IT WINS \u2014 AND REMOVING THAT FILE PUTS THE SERVICE BACK EXACTLY AS THE "
                + "PACKAGE LEFT IT. THE CONTENT TRAVELS ON STANDARD INPUT, NOT IN A COMMAND, SO IT NEVER "
                + "APPEARS IN THE PROCESS LIST OR IN SUDO'S LOG.");

        Label deleting = note("A SERVICE CAN ALSO BE TAKEN OFF THE MACHINE ALTOGETHER, FROM THE FOOT OF "
                + "ITS CARD OR FROM A RIGHT CLICK IN THE TREE. IT IS STOPPED, DISABLED, AND THEN ITS FILE IS "
                + "DELETED \u2014 IN THAT ORDER, BECAUSE DELETING THE FILE OF A RUNNING SERVICE WOULD LEAVE "
                + "THE PROCESS UP WITH NOTHING BEHIND IT. NOTHING UNDOES IT, SO IT ASKS FOR THE NAME BACK "
                + "FIRST.");

        Label onlyYours = note("AND ONLY A UNIT IN /ETC/SYSTEMD/SYSTEM, WHICH IS WHERE A UNIT PUT THERE BY "
                + "HAND LIVES. A SERVICE THAT CAME WITH A PACKAGE SITS IN /USR/LIB/SYSTEMD/SYSTEM AND THAT "
                + "FILE BELONGS TO THE PACKAGE: DELETING IT WOULD LEAVE THE PACKAGE INSTALLED AND BROKEN, AND "
                + "THE NEXT UPGRADE WOULD PUT IT BACK. THE CARD SAYS SO IN PLACE OF THE BUTTON, AND NAMES THE "
                + "FILE IT READ.");

        Label door = note("TWO OF THEM ARE REFUSED OUTRIGHT RATHER THAN CONFIRMED: STOPPING OR DISABLING SSHD, "
                + "OR THE NETWORK UNDER IT. THAT IS THE ONE MISTAKE WITH NO WAY BACK \u2014 THE SERVER KEEPS "
                + "RUNNING, PERFECTLY HEALTHY, AND YOU CANNOT REACH IT AGAIN. BEING SURE IS NOT THE PROBLEM "
                + "THERE; A MIS-CLICK IS. RESTARTING SSHD IS ALLOWED: IT KEEPS THE SESSIONS ALREADY OPEN, SO "
                + "THIS WINDOW SURVIVES IT.");

        Label safety = note("EVERY COMMAND KUBO SENDS IS LISTED IN THE LOGS \u2014 THE COMMANDS THEMSELVES, "
                + "NEVER WHAT THE SERVER ANSWERED. THE VERB IS ALWAYS ONE OF FIVE FIXED WORDS AND THE UNIT "
                + "NAME IS QUOTED, SO NOTHING YOU CAN TYPE BECOMES A SECOND COMMAND. EVERY READING IS PINNED "
                + "TO ONE LANGUAGE WITH LC_ALL=C, SO A SERVER SET TO ANOTHER ONE IS READ RIGHT RATHER THAN "
                + "READ WRONG.");

        VBox body = new VBox(14, label("WHAT THIS SCREEN IS FOR", "series"), what, two,
                label("HOW IT IS LAID OUT", "series"), layout,
                label("FOLLOWING A SERVICE", "series"), growing, why, again, moving,
                label("THE FIVE GROUPS", "series"), banks, falseAlarm,
                label("THE LAST BOOT", "series"), boot,
                label("WHAT YOU CAN CHANGE", "series"), acting, holding, signals, settings,
                deleting, onlyYours, door,
                label("WHAT KUBO RUNS", "series"), reads, safety);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(560);
        scroll.setMaxWidth(620);

        getChildren().addAll(header, scroll);
    }

    private static Rectangle swatch(Color colour) {
        Rectangle mark = new Rectangle(14, 10, colour);
        mark.setArcWidth(2);
        mark.setArcHeight(2);
        return mark;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(560);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
