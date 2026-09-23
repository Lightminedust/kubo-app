package com.kubo.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Selecting rows of a list to copy them: click, shift-click, ctrl-click or drag across, then Ctrl+C or a right
 * click. Only the selected rows are copied, in the order they are shown.
 *
 * <p>Any list can take it; how a row reads once copied is the list's to say.
 */
public final class ListSelect {

    /** One way of copying the selection, named as the right-click menu shows it. */
    public record Copy<T>(String name, Function<List<T>, String> text) {
    }

    private static final String ANCHOR = "kubo.list-select.anchor";

    private ListSelect() {
    }

    /**
     * @param copies the first is what Ctrl+C does; each one is an entry of the right-click menu
     * @param copied told how many rows went to the clipboard
     */
    @SafeVarargs
    public static <T> void install(ListView<T> list, IntConsumer copied, Copy<T>... copies) {
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copy(list, copies[0], copied);
                event.consume();
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                list.getSelectionModel().selectAll();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && !list.getSelectionModel().isEmpty()) {
                list.getSelectionModel().clearSelection();
                event.consume();
            }
        });

        List<MenuItem> items = new ArrayList<>();
        for (Copy<T> copy : copies) {
            MenuItem item = new MenuItem(copy.name());
            item.setOnAction(event -> copy(list, copy, copied));
            items.add(item);
        }
        MenuItem all = new MenuItem("SELECT ALL");
        all.setOnAction(event -> list.getSelectionModel().selectAll());
        MenuItem none = new MenuItem("SELECT NONE");
        none.setOnAction(event -> list.getSelectionModel().clearSelection());
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(items);
        menu.getItems().addAll(new SeparatorMenuItem(), all, none);
        menu.setOnShowing(event -> {
            int count = list.getSelectionModel().getSelectedIndices().size();
            for (int i = 0; i < items.size(); i++) {
                items.get(i).setText(copies[i].name() + (count > 1 ? "  ·  " + count : ""));
                items.get(i).setDisable(count == 0);
            }
            none.setDisable(count == 0);
        });
        list.setContextMenu(menu);
    }

    /**
     * Lets a drag that starts on this cell stretch the selection over every row it crosses. Called once, from
     * the cell's constructor.
     */
    public static void dragAcross(ListCell<?> cell) {
        cell.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (!cell.isEmpty() && !event.isShiftDown() && cell.getListView() != null) {
                cell.getListView().getProperties().put(ANCHOR, cell.getIndex());
            }
        });
        cell.setOnDragDetected(event -> {
            if (!cell.isEmpty() && event.isPrimaryButtonDown()) {
                cell.startFullDrag();
            }
        });
        cell.setOnMouseDragEntered(event -> {
            ListView<?> list = cell.getListView();
            if (list == null || !(list.getProperties().get(ANCHOR) instanceof Integer anchor)
                    || anchor >= list.getItems().size()) {
                return;
            }
            int to = cell.isEmpty() ? list.getItems().size() - 1 : cell.getIndex();
            list.getSelectionModel().clearSelection();
            list.getSelectionModel().selectRange(Math.min(anchor, to), Math.max(anchor, to) + 1);
        });
    }

    /** The selected rows, top to bottom. */
    public static <T> List<T> selected(ListView<T> list) {
        return list.getSelectionModel().getSelectedIndices().stream().sorted()
                .filter(index -> index >= 0 && index < list.getItems().size())
                .map(list.getItems()::get).toList();
    }

    private static <T> void copy(ListView<T> list, Copy<T> copy, IntConsumer copied) {
        List<T> rows = selected(list);
        if (rows.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(copy.text().apply(rows));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        copied.accept(rows.size());
    }
}
