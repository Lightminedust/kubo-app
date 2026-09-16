package com.kubo.ui.files;

import com.kubo.files.FileBrowser;
import com.kubo.model.files.Account;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The right column of the file manager: what the selected entry is, what the user may do with it, the size of a
 * folder measured on demand, and the legend of the tree.
 */
final class DetailsPanel extends VBox {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault());

    private final FileBrowser browser;
    private final Executor worker;
    private final BranchCanvas canvas;

    private final Label nameLabel = label("—", "files-title");
    private final Label pathValue = label("", "mono-muted");
    private final Label typeValue = label("—", "metric-value");
    private final Label sizeValue = label("—", "metric-value");
    private final Label itemsValue = label("—", "metric-value");
    private final Label modifiedValue = label("—", "metric-value");
    private final Label permissionsValue = label("—", "metric-value");
    private final Label ownerValue = label("—", "metric-value");
    private final Label linkValue = label("—", "metric-value");
    private final Label accessValue = label("", "metric-label");

    private final Map<String, Long> measured = new HashMap<>();
    private final Set<String> measuring = new HashSet<>();
    private String selectedPath;

    /**
     * @param browser null without a server (previews): folder sizes are then not measured
     */
    DetailsPanel(FileBrowser browser, Executor worker, BranchCanvas canvas) {
        this.browser = browser;
        this.worker = worker;
        this.canvas = canvas;

        nameLabel.setWrapText(true);
        nameLabel.setMinHeight(Region.USE_PREF_SIZE);
        pathValue.setWrapText(true);
        pathValue.setMinHeight(Region.USE_PREF_SIZE);
        linkValue.setWrapText(true);
        accessValue.setWrapText(true);
        accessValue.setMinHeight(Region.USE_PREF_SIZE);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(9);
        String[] names = {"TYPE", "SIZE", "ITEMS", "MODIFIED", "MODE", "OWNER", "LINK"};
        Label[] values = {typeValue, sizeValue, itemsValue, modifiedValue, permissionsValue, ownerValue, linkValue};
        for (int i = 0; i < names.length; i++) {
            grid.add(label(names[i], "metric-label"), 0, i);
            grid.add(values[i], 1, i);
        }

        Label hint = label("RIGHT-CLICK AN ENTRY: EDIT, RENAME, COPY, MOVE, PERMISSIONS, DELETE  ·  CLICK THE EMPTY "
                + "SPACE OF A FOLDER'S BRANCH: NEW FILE, NEW FOLDER, PERMISSIONS  ·  ENTER OPENS, F2 RENAMES, DEL "
                + "DELETES  ·  DOUBLE-CLICK A FOLDER TO OPEN IT, BACKSPACE CLOSES THE LAST ONE  ·  DRAG, THROW OR "
                + "WHEEL TO MOVE, CTRL+WHEEL TO ZOOM  ·  SORT EACH COLUMN FROM ITS HEADER", "lede");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        VBox legend = new VBox(6,
                legendRow(BranchStyle.FOLDER, "FOLDER  ·  SIZE = ITEMS INSIDE", false),
                legendRow(BranchStyle.CODE, "CODE, CONFIG & TEXT  ·  SIZE = WEIGHT", false),
                legendRow(BranchStyle.MEDIA, "IMAGES, VIDEO, AUDIO, PDF", false),
                legendRow(BranchStyle.ARCHIVE, "ARCHIVES, BINARIES, DATABASES", false),
                legendRow(BranchStyle.OTHER, "OTHER", false),
                legendRow(BranchStyle.LINK, "LINK", true),
                legendRow(Color.web("#ff2d55"), "NO PERMISSION  ·  RED LINE AND FIBER", true));

        Label legendTitle = label("LEGEND", "series");
        getChildren().addAll(label("DETAILS", "series"), nameLabel, pathValue, accessValue, grid, legendTitle,
                legend, hint);
        VBox.setMargin(nameLabel, new Insets(12, 0, 4, 0));
        VBox.setMargin(accessValue, new Insets(6, 0, 0, 0));
        VBox.setMargin(grid, new Insets(18, 0, 0, 0));
        VBox.setMargin(legendTitle, new Insets(28, 0, 10, 0));
        VBox.setMargin(hint, new Insets(22, 0, 0, 0));
        setPadding(new Insets(20, 40, 20, 12));
        setPrefWidth(310);
        setMinWidth(280);
    }

    /**
     * A message instead of an entry, e.g. while the home folder is being located.
     */
    void status(String title, String detail) {
        nameLabel.setText(title);
        pathValue.setText(detail);
    }

    void show(RemoteEntry entry, Account account, boolean elevated) {
        selectedPath = entry.path();
        nameLabel.setText(ActionCard.displayName(entry));
        pathValue.setText(entry.path());
        typeValue.setText(ActionCard.typeName(entry));
        typeValue.setStyle("-fx-text-fill: " + web(BranchStyle.color(entry)) + ";");
        modifiedValue.setText(entry.modified().equals(Instant.EPOCH) ? "—" : DATE.format(entry.modified()));
        permissionsValue.setText(entry.permissions().isEmpty() ? "—" : entry.permissions());
        ownerValue.setText(entry.owner().isEmpty() ? "—" : entry.owner() + " : " + entry.group());
        linkValue.setText(entry.linkTarget().isEmpty() ? "—" : entry.linkTarget());
        accessValue.setText(ActionCard.accessText(account, entry, elevated));
        itemsValue.setText(!entry.isDirectory() ? "—"
                : !entry.readable() ? "LOCKED"
                : entry.childCount() >= 0 ? String.valueOf(entry.childCount()) : "—");

        if (!entry.isDirectory()) {
            sizeValue.setText(Format.bytes(entry.sizeBytes()));
        } else if (measured.containsKey(entry.path())) {
            sizeValue.setText(Format.bytes(measured.get(entry.path())));
        } else if (entry.readable() && browser != null) {
            measure(entry.path());
        } else {
            sizeValue.setText("—");
        }
        canvas.setMeasuring(measuring.contains(entry.path()) ? entry.path() : null);
    }

    /**
     * An entry is gone: its details are cleared if they were shown.
     */
    void forget(String path) {
        if (selectedPath != null && (selectedPath.equals(path) || selectedPath.startsWith(path + "/"))) {
            selectedPath = null;
            nameLabel.setText("—");
            pathValue.setText("");
            for (Label value : new Label[]{typeValue, sizeValue, itemsValue, modifiedValue, permissionsValue,
                    ownerValue, linkValue}) {
                value.setText("—");
            }
            accessValue.setText("");
        }
    }

    /**
     * Something changed on the server: folder sizes measured before may be wrong now.
     */
    void clearMeasurements() {
        measured.clear();
    }

    private void measure(String path) {
        sizeValue.setText("MEASURING…");
        if (!measuring.add(path)) {
            return;
        }
        worker.execute(() -> {
            OptionalLong size = browser.measure(path);
            Platform.runLater(() -> {
                measuring.remove(path);
                size.ifPresent(bytes -> measured.put(path, bytes));
                if (path.equals(selectedPath)) {
                    sizeValue.setText(size.isPresent() ? Format.bytes(size.getAsLong()) : "TOO LARGE OR NOT READABLE");
                    canvas.setMeasuring(null);
                }
            });
        });
    }

    private static HBox legendRow(Color color, String text, boolean ring) {
        Circle marker = new Circle(4);
        if (ring) {
            marker.setFill(Color.TRANSPARENT);
            marker.setStroke(color);
        } else {
            marker.setFill(color);
        }
        HBox row = new HBox(10, marker, label(text, "metric-label"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
