package com.kubo.ui.files;

import com.kubo.model.files.Account;
import com.kubo.model.files.RemoteEntry;
import javafx.geometry.Point2D;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;

import java.io.File;
import java.util.Optional;

/**
 * Files dragged from Explorer or from this computer's card: dropped on a folder's dot, a file's dot or a folder's
 * space, they are uploaded into that folder. The tree shows where the drop would go, or why it cannot.
 */
final class DropHandler {

    private final FilesView view;
    private final BranchCanvas canvas;

    DropHandler(FilesView view, StackPane map, BranchCanvas canvas) {
        this.view = view;
        this.canvas = canvas;
        map.addEventHandler(DragEvent.DRAG_OVER, this::over);
        map.addEventHandler(DragEvent.DRAG_EXITED, event -> canvas.setDropTarget(null, null));
        map.addEventHandler(DragEvent.DRAG_DROPPED, this::dropped);
    }

    /**
     * Why nothing can be uploaded into a folder, or null when it can.
     */
    static String problem(RemoteEntry folder, Account account) {
        if (!folder.readable()) {
            return "NO PERMISSION TO ENTER";
        }
        if (account != null && !account.canCreateIn(folder)) {
            return "NO WRITE PERMISSION HERE";
        }
        return null;
    }

    private void over(DragEvent event) {
        if (!event.getDragboard().hasFiles() || view.picker() != null) {
            return;
        }
        LocalCard localCard = view.localCard();
        TransferPanel transfers = view.transfers();
        if (localCard != null && FloatingLayer.contains(localCard, event.getSceneX(), event.getSceneY())
                || transfers.isVisible() && FloatingLayer.contains(transfers, event.getSceneX(), event.getSceneY())) {
            canvas.setDropTarget(null, null);
            return;
        }
        Optional<RemoteEntry> folder = folderUnder(event);
        if (folder.isEmpty()) {
            canvas.setDropTarget(null, null);
            return;
        }
        String problem = problem(folder.get(), view.account());
        canvas.setDropTarget(folder.get(), problem);
        if (problem == null) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void dropped(DragEvent event) {
        Optional<RemoteEntry> folder = folderUnder(event);
        canvas.setDropTarget(null, null);
        if (!event.getDragboard().hasFiles() || folder.isEmpty() || problem(folder.get(), view.account()) != null) {
            return;
        }
        view.upload(event.getDragboard().getFiles().stream().map(File::toPath).toList(), folder.get());
        event.setDropCompleted(true);
        event.consume();
    }

    private Optional<RemoteEntry> folderUnder(DragEvent event) {
        Point2D point = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
        return canvas.folderAt(point.getX(), point.getY());
    }
}
