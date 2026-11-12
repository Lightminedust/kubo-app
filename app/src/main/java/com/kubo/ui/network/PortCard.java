package com.kubo.ui.network;

import com.kubo.model.server.ListeningPort.Protocol;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Opens or blocks a port at the firewall, from the map: which port, which protocol, and for whom.
 *
 * <p>The card only gathers the answer and says, before anything is sent, what it will do in plain words. The
 * screen behind it sends one of the firewall's own fixed commands, with the port and the address checked
 * against strict patterns first, and asks for root the way every other change does.
 *
 * <p>Blocking the port this very connection came in on, for everyone, is refused here: that is the one rule
 * that ends the session and keeps it ended. The firewall card can still do it, with its own warning.
 */
final class PortCard extends VBox {

    /** What the screen does with the answer. */
    interface Hands {
        /**
         * @param open   true to let it in, false to keep it out
         * @param source an address or a network, or empty for everyone
         * @param done   told why it failed, or null once it worked
         */
        void apply(boolean open, int port, Protocol protocol, String source, java.util.function.Consumer<String> done);
    }

    private final Button openChoice = chip("open");
    private final Button blockChoice = chip("block");
    private final Button tcpChoice = chip("tcp");
    private final Button udpChoice = chip("udp");
    private final TextField portField = new TextField();
    private final TextField sourceField = new TextField();
    private final Label says = new Label();
    private final Label status = new Label();
    private final Button confirm = new Button();
    private final int sshPort;
    private boolean opening;
    private Protocol protocol = Protocol.TCP;

    PortCard(boolean opening, int port, Protocol protocol, int sshPort, Hands hands, Runnable onClose) {
        this.opening = opening;
        this.sshPort = sshPort;
        this.protocol = protocol == null ? Protocol.TCP : protocol;
        getStyleClass().addAll("float-card", "help-card", "redis-help");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(16);
        setPadding(new Insets(24, 30, 24, 28));
        setMinWidth(460);

        Label title = new Label("a port at the firewall");
        title.getStyleClass().add("fetch-host");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("close");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        portField.getStyleClass().add("card-field");
        portField.setPromptText("8080");
        portField.setPrefColumnCount(6);
        if (port > 0) {
            portField.setText(String.valueOf(port));
        }
        sourceField.getStyleClass().add("card-field");
        sourceField.setPromptText("everyone, or an address");
        sourceField.setPrefColumnCount(24);

        openChoice.setOnAction(event -> {
            this.opening = true;
            refresh();
        });
        blockChoice.setOnAction(event -> {
            this.opening = false;
            refresh();
        });
        tcpChoice.setOnAction(event -> {
            this.protocol = Protocol.TCP;
            refresh();
        });
        udpChoice.setOnAction(event -> {
            this.protocol = Protocol.UDP;
            refresh();
        });
        portField.textProperty().addListener((observable, before, now) -> refresh());
        sourceField.textProperty().addListener((observable, before, now) -> refresh());

        GridPane form = new GridPane();
        form.setHgap(18);
        form.setVgap(10);
        form.add(key("do"), 0, 0);
        form.add(new HBox(6, openChoice, blockChoice), 1, 0);
        form.add(key("port"), 0, 1);
        HBox portRow = new HBox(10, portField, tcpChoice, udpChoice);
        portRow.setAlignment(Pos.CENTER_LEFT);
        form.add(portRow, 1, 1);
        form.add(key("from"), 0, 2);
        form.add(sourceField, 1, 2);

        says.getStyleClass().add("fetch-value");
        says.setWrapText(true);
        says.setMaxWidth(420);
        says.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("card-problem");
        status.setWrapText(true);
        status.setMaxWidth(420);
        status.setMinHeight(Region.USE_PREF_SIZE);

        Button cancel = new Button("cancel");
        cancel.getStyleClass().add("ghost-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> onClose.run());
        confirm.getStyleClass().addAll("primary-button", "compact-button");
        confirm.setFocusTraversable(false);
        confirm.setOnAction(event -> {
            String trouble = problem();
            if (trouble != null) {
                status.setText(trouble);
                return;
            }
            status.getStyleClass().setAll("label", "card-note");
            status.setText("sending…");
            confirm.setDisable(true);
            hands.apply(this.opening, number(), this.protocol, sourceField.getText().strip(), failed -> {
                confirm.setDisable(false);
                if (failed != null) {
                    status.getStyleClass().setAll("label", "card-problem");
                    status.setText(failed);
                }
            });
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(14, gap, cancel, confirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, form, says, status, buttons);
        refresh();
    }

    /** Says what will happen, and paints the choices. */
    private void refresh() {
        paint(openChoice, opening);
        paint(blockChoice, !opening);
        paint(tcpChoice, protocol == Protocol.TCP);
        paint(udpChoice, protocol == Protocol.UDP);
        int port = number();
        String source = sourceField.getText().strip();
        String which = (port > 0 ? String.valueOf(port) : "the port") + "/" + protocol.name().toLowerCase(Locale.ROOT);
        String who = source.isEmpty() ? "everyone on the internet" : source;
        says.setText(opening
                ? who + " will be able to reach " + which + "."
                : who.substring(0, 1).toUpperCase(Locale.ROOT) + who.substring(1) + " will be turned away from "
                        + which + ".");
        says.setStyle(opening && source.isEmpty() ? "-fx-text-fill: #ffb347;" : "");
        confirm.setText((opening ? "open " : "block ") + which);
        String trouble = problem();
        status.getStyleClass().setAll("label", "card-problem");
        status.setText(trouble == null || portField.getText().isBlank() ? "" : trouble);
    }

    /** Why it cannot be sent as it is, or null. */
    private String problem() {
        int port = number();
        if (port < 1 || port > 65535) {
            return "A port is a number between 1 and 65535.";
        }
        if (!opening && port == sshPort && sourceField.getText().isBlank()) {
            return "Port " + port + " is the one this connection uses. Blocking it for everyone would lock you "
                    + "out at the next connection, so it is not done from here.";
        }
        return null;
    }

    private int number() {
        try {
            return Integer.parseInt(portField.getText().strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void paint(Button button, boolean chosen) {
        button.getStyleClass().remove("service-chip-active");
        if (chosen) {
            button.getStyleClass().add("service-chip-active");
        }
    }

    private static Button chip(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("service-chip");
        button.setFocusTraversable(false);
        return button;
    }

    private static Label key(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("fetch-key");
        label.setMinWidth(50);
        return label;
    }
}
