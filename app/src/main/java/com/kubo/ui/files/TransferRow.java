package com.kubo.ui.files;

import com.kubo.ui.files.Transfer.State;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * How one transfer looks in the panel: its name, size, a line of particles for progress, what is happening and,
 * when needed, buttons to decide.
 */
final class TransferRow extends VBox {

    private static final Color UP = BranchStyle.LOCAL;
    private static final Color DOWN = BranchStyle.CODE;

    private final Transfer transfer;
    private final Label nameLabel = label("", "upload-name");
    private final Label sizeLabel = label("", "mono-muted");
    private final Label statusLabel = label("", "upload-status");
    private final Canvas bar;
    private final HBox actions = new HBox(14);
    private final Button close = new Button("✕");

    TransferRow(Transfer transfer, double width, Runnable onClose) {
        super(5);
        this.transfer = transfer;
        this.bar = new Canvas(width - 44, 12);

        Label arrow = label(transfer.download ? "⇩" : "⇪", transfer.download ? "transfer-down" : "transfer-up");
        arrow.setMinWidth(Region.USE_PREF_SIZE);
        nameLabel.setText(transfer.name);
        nameLabel.setMinWidth(0);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        sizeLabel.setMinWidth(Region.USE_PREF_SIZE);
        close.getStyleClass().add("upload-close");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox top = new HBox(8, arrow, nameLabel, sizeLabel, close);
        top.setAlignment(Pos.CENTER_LEFT);
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        actions.setAlignment(Pos.CENTER_LEFT);
        hideActions();
        getChildren().addAll(top, bar, statusLabel, actions);
        getStyleClass().add("upload-row");
    }

    void showActions(List<Button> buttons) {
        actions.getChildren().setAll(buttons);
        actions.setVisible(true);
        actions.setManaged(true);
    }

    void hideActions() {
        actions.setVisible(false);
        actions.setManaged(false);
    }

    /**
     * Shows the transfer's current state; {@code clock} animates the particles.
     */
    void update(double clock) {
        nameLabel.setText(transfer.name);
        sizeLabel.setText(transfer.sizeText());
        close.setText(transfer.running() ? "CANCEL" : "✕");
        statusLabel.setText(transfer.statusText());
        statusLabel.getStyleClass().setAll("label", switch (transfer.state) {
            case DONE -> "upload-status-done";
            case FAILED, CONFLICT -> "upload-status-error";
            default -> "upload-status";
        });
        drawBar(clock);
    }

    /**
     * Sixty particles: lit as the bytes travel, the leading one glowing; waiting ones shimmer.
     * An upload fills from the left, a download from the right, as the flow goes toward this computer.
     */
    private void drawBar(double clock) {
        GraphicsContext gc = bar.getGraphicsContext2D();
        double width = bar.getWidth();
        double height = bar.getHeight();
        gc.clearRect(0, 0, width, height);
        int count = 60;
        double step = width / count;
        State state = transfer.state;
        double shown = switch (state) {
            case DONE -> 1;
            case SENDING -> transfer.total > 0 ? transfer.fraction() : (clock * 0.3) % 1;
            default -> 0;
        };
        Color lit = switch (state) {
            case DONE -> BranchStyle.CODE;
            case FAILED, CONFLICT -> Color.web("#ff2d55");
            case CANCELLED -> Color.web("#4a4a57");
            default -> transfer.download ? DOWN : UP;
        };
        int litCount = (int) Math.floor(shown * count);
        for (int slot = 0; slot < count; slot++) {
            int i = transfer.download ? count - 1 - slot : slot;
            double x = i * step + step / 2;
            boolean on = slot < litCount;
            double y = height / 2 + (state == State.SENDING && on ? Math.sin(slot * 0.9 + clock * 3) * 1.5 : 0);
            double size = on ? 3 : 1.6;
            gc.setFill(on ? lit : Color.web("#2a2a34"));
            gc.setGlobalAlpha(on ? 0.95 : 1);
            if (state == State.READING || state == State.WAITING) {
                double wave = 0.5 + 0.5 * Math.sin(clock * 5 - slot * 0.35);
                gc.setFill(transfer.download ? DOWN : UP);
                gc.setGlobalAlpha(0.15 + 0.5 * wave * (state == State.READING ? 1 : 0.4));
                size = 1.6 + wave;
            }
            if (state == State.FAILED || state == State.CONFLICT) {
                gc.setFill(lit);
                gc.setGlobalAlpha(slot % 3 == 0 ? 0.8 : 0.25);
            }
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }
        if (state == State.SENDING && litCount < count) {
            int head = transfer.download ? count - 1 - litCount : litCount;
            double x = head * step + step / 2;
            gc.setFill(Color.WHITE);
            gc.setGlobalAlpha(0.3);
            gc.fillOval(x - 6, height / 2 - 6, 12, 12);
            gc.setGlobalAlpha(1);
            gc.fillOval(x - 2, height / 2 - 2, 4, 4);
        }
        if (state == State.DONE && transfer.finishedAt >= 0 && clock - transfer.finishedAt < 1.2) {
            double t = (clock - transfer.finishedAt) / 1.2;
            gc.setFill(Color.WHITE);
            gc.setGlobalAlpha(1 - t);
            gc.fillRect(transfer.download ? width * (1 - t) : 0, height / 2 - 0.5, width * t, 1);
        }
        gc.setGlobalAlpha(1);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
