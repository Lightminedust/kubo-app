package com.kubo.ui.services;

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

/**
 * A card explaining how to make a service on this screen, and what each field becomes in the file.
 *
 * <p>Making a service is the one thing on the services screen that is not reading something that already
 * exists. Every other card explains what a server is doing; this one explains what the user is about to put
 * on it, which is a different job: what a unit file is, why a path has to be absolute, why a script has to be
 * executable, and what the tree in the middle is for.
 */
final class BuilderHelp extends VBox {

    BuilderHelp(Runnable onClose) {
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
        HBox header = new HBox(12, label("MAKING A SERVICE", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label what = note("A SERVICE IS A FILE. IT SAYS WHAT TO RUN, WHERE TO RUN IT, WHO AS, AND WHAT TO DO "
                + "WHEN IT STOPS. SYSTEMD DOES THE REST: IT STARTS IT, WATCHES IT, AND BRINGS IT BACK AFTER A "
                + "REBOOT. THAT IS THE DIFFERENCE BETWEEN A SCRIPT YOU RAN ONCE IN A TERMINAL AND SOMETHING "
                + "THE MACHINE WILL KEEP RUNNING WITHOUT YOU.");

        Label where = note("WHAT THIS SCREEN WRITES GOES TO /ETC/SYSTEMD/SYSTEM, WHICH IS WHERE UNITS PUT "
                + "THERE BY HAND LIVE. NOTHING A PACKAGE INSTALLED IS TOUCHED, AND KUBO WILL NOT WRITE OVER A "
                + "UNIT THAT IS ALREADY THERE — THE REFUSAL IS IN THE COMMAND ITSELF, NOT JUST IN THIS "
                + "SCREEN.");

        Label tree = note("THE TREE IN THE MIDDLE IS THE FILE SCREEN, HERE SO YOU CAN FIND THE THING YOU "
                + "WANT TO RUN WITHOUT LEAVING. IT MOVES THE SAME WAY: THE WHEEL TRAVELS, CONTROL AND THE "
                + "WHEEL ZOOM, A CLICK OPENS A FOLDER INTO THE NEXT COLUMN.");

        Label rightClick = note("RIGHT CLICK IN IT AND YOU CAN MAKE A FILE OR A FOLDER WHERE YOU ARE, EDIT A "
                + "FILE, RENAME ONE, OR DELETE ONE. MAKING A FILE OPENS THE EDITOR ON IT AT ONCE, BECAUSE "
                + "MAKING IT WAS NOT THE POINT — WRITING IN IT WAS. ANYTHING IN /OPT OR /ETC BELONGS TO "
                + "ROOT, SO TURN ON ROOT IN THE HEADER FIRST OR THE SERVER WILL SAY NO.");

        Label executable = note("A SCRIPT YOU HAVE JUST MADE IS NOT EXECUTABLE. NEW FILES ARE WRITTEN 0644, "
                + "WHICH IS RIGHT FOR A CONFIGURATION FILE AND USELESS FOR A SCRIPT: THE SERVICE WOULD BE "
                + "WRITTEN, SYSTEMD WOULD ACCEPT IT, AND IT WOULD FAIL AT EVERY START WITH \"PERMISSION "
                + "DENIED\". PICK THE FILE AS WHAT IT RUNS AND THIS SCREEN SAYS SO UNDER THE FIELD, WITH THE "
                + "BUTTON THAT FIXES IT.");

        Label rights = note("THAT BUTTON ADDS THE RIGHT TO RUN IT FOR WHOEVER COULD ALREADY READ IT: 0644 "
                + "BECOMES 0755, 0600 BECOMES 0700. A FILE EVERYBODY CAN READ BECOMES ONE EVERYBODY CAN RUN, "
                + "AND A PRIVATE ONE STAYS PRIVATE.");

        Label absolute = note("EVERY PATH HAS TO BE ABSOLUTE. A SERVICE HAS NO PATH OF ITS OWN AND NO SHELL "
                + "AROUND IT, SO \"PYTHON3 APP.PY\" MEANS NOTHING TO IT — IT NEEDS /USR/BIN/PYTHON3 "
                + "/OPT/APP/APP.PY. FOR ANYTHING THAT WANTS A SHELL, PIPES OR VARIABLES, POINT IT AT "
                + "/BIN/SH /PATH/TO/YOUR/SCRIPT.SH AND PUT THE SHELL WORK INSIDE THE SCRIPT.");

        GridPane fields = new GridPane();
        fields.setHgap(18);
        fields.setVgap(6);
        fields.add(label("THE FIELD", "metric-label"), 0, 0);
        fields.add(label("BECOMES", "metric-label"), 1, 0);
        String[] rows = {
            "WHAT TO CALL IT", "the file name: /etc/systemd/system/<name>.service",
            "WHAT IT IS FOR", "Description=  — what the services screen shows beside it",
            "WHAT IT RUNS", "ExecStart=  — an absolute path, quoted if it has a space in it",
            "WHERE IT RUNS", "WorkingDirectory=  — the folder it starts in",
            "WHO IT RUNS AS", "User=  — root when left empty, which is more than most services need",
            "WHEN IT DIES", "Restart=no | on-failure | always, and RestartSec=",
            "START IT AT EVERY BOOT", "systemctl enable, once the file is written",
            "START IT NOW", "systemctl start, once it is enabled",
        };
        for (int i = 0; i < rows.length; i += 2) {
            Label name = label(rows[i], "redis-row-name");
            name.setMinWidth(200);
            fields.add(name, 0, i / 2 + 1);
            fields.add(label(rows[i + 1], "mono-muted"), 1, i / 2 + 1);
        }

        Label byHand = note("THE BOX AT THE BOTTOM IS THE FILE ITSELF, AND YOU CAN TYPE IN IT. A UNIT FILE "
                + "HAS SOME THIRTY SETTINGS THIS SCREEN DOES NOT OFFER AND SOONER OR LATER YOU WILL WANT ONE "
                + "OF THEM. ONCE YOU TOUCH IT, THE FIELDS ABOVE STOP REWRITING IT AND WHAT IS IN THE BOX IS "
                + "WHAT GETS WRITTEN — THERE IS A BUTTON TO BUILD IT FROM THE FIELDS AGAIN.");

        Label sent = note("CREATE IT RUNS FOUR THINGS IN ORDER, AND STOPS AT THE FIRST ONE THAT FAILS, "
                + "SAYING WHICH: A UNIT THAT WAS WRITTEN BUT NOT RELOADED IS A DIFFERENT PROBLEM FROM ONE "
                + "THAT RELOADED AND WOULD NOT START.");

        GridPane runs = new GridPane();
        runs.setHgap(18);
        runs.setVgap(6);
        String[] commands = {
            "WRITING THE FILE", "cat > /etc/systemd/system/<name>.service, then chmod 0644",
            "TELLING SYSTEMD", "systemctl daemon-reload",
            "AT EVERY BOOT", "systemctl enable <name>.service",
            "AND NOW", "systemctl start <name>.service",
        };
        for (int i = 0; i < commands.length; i += 2) {
            Label name = label(commands[i], "redis-row-name");
            name.setMinWidth(200);
            runs.add(name, 0, i / 2);
            runs.add(label(commands[i + 1], "mono-muted"), 1, i / 2);
        }

        Label safety = note("THE FILE TRAVELS ON STANDARD INPUT, NOT INSIDE A COMMAND, SO IT NEVER APPEARS "
                + "IN THE PROCESS LIST OR IN SUDO'S LOG HOWEVER LONG OR HOWEVER STRANGE IT IS. AND AS "
                + "EVERYWHERE ELSE IN KUBO, THE LOGS LIST EVERY COMMAND SENT — THE COMMANDS THEMSELVES, "
                + "NEVER WHAT THE SERVER ANSWERED.");

        Label after = note("ONCE IT IS WRITTEN IT IS A SERVICE LIKE ANY OTHER: IT APPEARS IN THE TREE, IT "
                + "CAN BE STOPPED, HELD BACK, GIVEN SETTINGS OF ITS OWN, AND DELETED AGAIN FROM THE FOOT OF "
                + "ITS CARD.");

        VBox body = new VBox(14, label("WHAT A SERVICE IS", "series"), what, where,
                label("THE TREE IN THE MIDDLE", "series"), tree, rightClick,
                label("MAKING A SCRIPT RUNNABLE", "series"), executable, rights,
                label("PATHS", "series"), absolute,
                label("WHAT EACH FIELD BECOMES", "series"), fields, byHand,
                label("WHAT CREATE IT RUNS", "series"), sent, runs, safety,
                label("AFTERWARDS", "series"), after);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(560);
        scroll.setMaxWidth(620);

        getChildren().addAll(header, scroll);
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(600);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
