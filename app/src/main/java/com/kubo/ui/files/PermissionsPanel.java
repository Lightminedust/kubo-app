package com.kubo.ui.files;

import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.PermissionMode.Right;
import com.kubo.model.files.PermissionMode.Who;
import com.kubo.model.files.RemoteEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shows and edits a mode three ways that stay in step: a grid of rights, the digits chmod takes and the letters
 * ls prints, with a small lexicon explaining each of them.
 */
final class PermissionsPanel extends VBox {

    private static final Map<Who, String> CLASS_STYLE = Map.of(
            Who.OWNER, "owner", Who.GROUP, "group", Who.OTHERS, "others");

    private final RemoteEntry target;
    private final PermissionMode original;
    private PermissionMode mode;
    private boolean updating;

    private final Label octal = label("", "perm-octal");
    private final Map<Who, Label> letters = new EnumMap<>(Who.class);
    private final Map<Who, Label> digits = new EnumMap<>(Who.class);
    private final Map<Who, Map<Right, ToggleButton>> cells = new EnumMap<>(Who.class);
    private final Label summary = label("", "card-note");
    private final TextField octalField = new TextField();
    private final Button apply = new Button();
    private final Button back = new Button("BACK");
    private final Label status = label("", "card-problem");

    private Consumer<PermissionMode> onApply = mode -> {
    };
    private Runnable onBack = () -> {
    };

    /**
     * @param lockReason why the mode can only be read, or null when it can be changed
     */
    PermissionsPanel(RemoteEntry target, PermissionMode mode, String lockReason) {
        this.target = target;
        this.original = mode;
        this.mode = mode;
        boolean editable = lockReason == null;
        setSpacing(12);

        Label name = label(target.path().equals("/") ? "/" : target.name(), "card-title");
        name.setWrapText(true);
        Label owners = label("OWNER " + target.owner() + "  ·  GROUP " + target.group(), "mono-muted");
        VBox title = new VBox(3, label("PERMISSIONS", "series"), name, owners);

        Label type = label(typeLetter(target), "perm-letters");
        type.getStyleClass().add("perm-type");
        HBox letterRow = new HBox(type);
        for (Who who : Who.values()) {
            Label group = label("", "perm-letters");
            group.getStyleClass().add("perm-" + CLASS_STYLE.get(who));
            letters.put(who, group);
            letterRow.getChildren().add(group);
            HBox.setMargin(group, new Insets(0, 0, 0, 5));
        }
        letterRow.setAlignment(Pos.BASELINE_LEFT);
        HBox readout = new HBox(18, octal, letterRow);
        readout.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = buildGrid(editable);

        octalField.getStyleClass().add("card-field");
        octalField.setPrefColumnCount(4);
        octalField.setMaxWidth(70);
        octalField.setDisable(!editable);
        octalField.textProperty().addListener((observable, previous, text) -> {
            if (!updating) {
                PermissionMode.parseOctal(text).ifPresent(parsed -> setMode(parsed, false));
            }
        });
        HBox typed = new HBox(12, label("OR TYPE THE DIGITS", "metric-label"), octalField);
        typed.setAlignment(Pos.CENTER_LEFT);

        summary.setWrapText(true);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        List<Node> controls = new ArrayList<>(List.of(readout, grid, summary, typed));
        String special = specialNote(mode);
        if (!special.isEmpty()) {
            Label note = label(special, "card-note");
            note.setWrapText(true);
            note.setMinHeight(Region.USE_PREF_SIZE);
            controls.add(note);
        }
        if (!editable) {
            Label lock = label(lockReason, "card-problem");
            lock.setWrapText(true);
            lock.setMinHeight(Region.USE_PREF_SIZE);
            controls.add(lock);
        }

        apply.getStyleClass().addAll("primary-button", "compact-button");
        apply.setVisible(editable);
        apply.setManaged(editable);
        apply.setOnAction(event -> onApply.accept(this.mode));
        back.getStyleClass().add("ghost-button");
        back.setOnAction(event -> onBack.run());
        HBox buttons = new HBox(18, apply, back);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(buttons, new Insets(6, 0, 0, 0));
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        controls.add(buttons);
        controls.add(status);

        // Settings on the left, the lexicon beside them, so the card stays short enough for small windows.
        VBox left = new VBox(12);
        left.getChildren().setAll(controls);
        left.setPrefWidth(318);
        left.setMinWidth(318);
        VBox lexicon = buildLexicon();
        HBox.setHgrow(lexicon, Priority.ALWAYS);
        Region divider = new Region();
        divider.getStyleClass().add("card-rule");
        divider.setStyle("-fx-pref-width: 1px; -fx-max-width: 1px; -fx-max-height: 100000px;");
        HBox columns = new HBox(22, left, divider, lexicon);
        getChildren().setAll(title, columns);

        setMode(mode, true);
    }

    void setOnApply(Consumer<PermissionMode> onApply) {
        this.onApply = onApply;
    }

    void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    void working(String text) {
        status.getStyleClass().setAll("label", "card-note");
        status.setText(text);
        apply.setDisable(true);
        back.setDisable(true);
    }

    void failed(String problem) {
        status.getStyleClass().setAll("label", "card-problem");
        status.setText(problem);
        apply.setDisable(mode.equals(original));
        back.setDisable(false);
    }

    PermissionMode mode() {
        return mode;
    }

    /**
     * Changes the rights shown, for previews.
     */
    void setMode(PermissionMode next) {
        setMode(next, true);
    }

    private void setMode(PermissionMode next, boolean fromOutsideField) {
        updating = true;
        mode = next;
        octal.setText(next.octal());
        String symbolic = next.symbolic();
        for (Who who : Who.values()) {
            int start = who.ordinal() * 3;
            letters.get(who).setText(symbolic.substring(start, start + 3));
            digits.get(who).setText(String.valueOf(next.digit(who)));
            for (Right right : Right.values()) {
                ToggleButton cell = cells.get(who).get(right);
                boolean granted = next.has(who, right);
                cell.setSelected(granted);
                cell.setText(granted ? String.valueOf(right.letter()) : "-");
            }
        }
        if (fromOutsideField) {
            octalField.setText(next.octal());
        }
        summary.setText(summarize(next));
        apply.setText(next.equals(original) ? "NO CHANGE" : "APPLY " + next.octal());
        apply.setDisable(next.equals(original));
        updating = false;
    }

    private GridPane buildGrid(boolean editable) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        Right[] rights = Right.values();
        for (int column = 0; column < rights.length; column++) {
            Right right = rights[column];
            Label head = label(right.name() + "  " + right.letter() + " " + right.value(), "metric-label");
            grid.add(head, column + 1, 0);
        }
        grid.add(label("=", "metric-label"), 4, 0);

        int row = 1;
        for (Who who : Who.values()) {
            String owner = switch (who) {
                case OWNER -> target.owner();
                case GROUP -> target.group();
                case OTHERS -> "everyone else";
            };
            Label name = label(who.name(), "metric-label");
            Label detail = label(owner, "mono-muted");
            VBox caption = new VBox(0, name, detail);
            caption.setMinWidth(92);
            grid.add(caption, 0, row);

            Map<Right, ToggleButton> byRight = new EnumMap<>(Right.class);
            for (int column = 0; column < rights.length; column++) {
                Right right = rights[column];
                ToggleButton cell = new ToggleButton("-");
                cell.getStyleClass().addAll("perm-cell", "perm-cell-" + CLASS_STYLE.get(who));
                cell.setDisable(!editable);
                cell.setFocusTraversable(false);
                cell.selectedProperty().addListener((observable, previous, selected) -> {
                    if (!updating) {
                        setMode(mode.with(who, right, selected), true);
                    }
                });
                byRight.put(right, cell);
                grid.add(cell, column + 1, row);
            }
            cells.put(who, byRight);
            Label digit = label("", "perm-digit");
            digit.getStyleClass().add("perm-" + CLASS_STYLE.get(who));
            digits.put(who, digit);
            grid.add(digit, 4, row);
            row++;
        }
        return grid;
    }

    private VBox buildLexicon() {
        boolean folder = target.isDirectory();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        String[][] rows = {
                {"r", "READ · 4", folder ? "see the names inside" : "open and read the content"},
                {"w", "WRITE · 2", folder ? "create, rename and delete what is inside" : "change the content"},
                {"x", "EXECUTE · 1", folder ? "enter it and reach what is inside" : "run it as a program or script"},
                {"-", "NONE · 0", "this right is not given"},
                {"7 5 0", "DIGITS", "each digit is the sum of its rights: 7 = 4+2+1 = rwx, 6 = rw-, 5 = r-x, 4 = r--, 0 = ---"},
                {"u g o", "ORDER", "first the owner, then the group, then everyone else: 750 = rwx r-x ---"},
                {"d - l", "FIRST LETTER", "the type, not a right: d folder, - file, l link"},
                {"s t", "SPECIAL", "setuid, setgid and sticky (as on /tmp) take the place of x; they are kept as they are"},
        };
        for (int i = 0; i < rows.length; i++) {
            Label key = label(rows[i][0], "lexicon-key");
            key.setMinWidth(Region.USE_PREF_SIZE);
            Label word = label(rows[i][1], "metric-label");
            word.setMinWidth(Region.USE_PREF_SIZE);
            Label text = label(rows[i][2], "lexicon-text");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            GridPane.setHgrow(text, Priority.ALWAYS);
            grid.add(key, 0, i);
            grid.add(word, 1, i);
            grid.add(text, 2, i);
        }
        return new VBox(8, label("LEXICON", "series"), grid);
    }

    private String summarize(PermissionMode next) {
        boolean folder = target.isDirectory();
        List<String> parts = new ArrayList<>();
        for (Who who : Who.values()) {
            List<String> rights = new ArrayList<>();
            if (next.has(who, Right.READ)) {
                rights.add(folder ? "list" : "read");
            }
            if (next.has(who, Right.WRITE)) {
                rights.add(folder ? "change inside" : "write");
            }
            if (next.has(who, Right.EXECUTE)) {
                rights.add(folder ? "enter" : "run");
            }
            String name = who == Who.OTHERS ? "everyone else" : who.name().toLowerCase(Locale.ROOT);
            parts.add(name + ": " + (rights.isEmpty() ? "nothing" : String.join(", ", rights)));
        }
        return String.join("  ·  ", parts).toUpperCase(Locale.ROOT);
    }

    private String specialNote(PermissionMode mode) {
        List<String> bits = new ArrayList<>();
        if (mode.hasSpecial(PermissionMode.SETUID)) {
            bits.add("SETUID: RUNS AS ITS OWNER");
        }
        if (mode.hasSpecial(PermissionMode.SETGID)) {
            bits.add(target.isDirectory() ? "SETGID: NEW ENTRIES INSIDE TAKE THIS GROUP" : "SETGID: RUNS AS ITS GROUP");
        }
        if (mode.hasSpecial(PermissionMode.STICKY)) {
            bits.add("STICKY: ONLY OWNERS DELETE THEIR OWN ENTRIES");
        }
        return bits.isEmpty() ? "" : String.join("  ·  ", bits) + "  ·  KEPT AS IS";
    }

    private static String typeLetter(RemoteEntry entry) {
        return switch (entry.type()) {
            case DIRECTORY -> "d";
            case LINK -> "l";
            case FILE -> "-";
            case OTHER -> entry.permissions().isEmpty() ? "?" : entry.permissions().substring(0, 1);
        };
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
