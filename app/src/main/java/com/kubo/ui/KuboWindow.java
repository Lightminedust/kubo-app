package com.kubo.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Objects;

/**
 * Kubo's borderless Windows frame: branded title bar, native-like window actions and resize edges.
 */
public final class KuboWindow extends BorderPane {

    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int TOP = 4;
    private static final int BOTTOM = 8;
    private static final double EDGE = 6;

    private final Stage stage;
    private final Button maximize = control("□", "MAXIMIZE");
    private int resizing;
    private double pressedX;
    private double pressedY;
    private double originalX;
    private double originalY;
    private double originalWidth;
    private double originalHeight;
    private double dragX;
    private double dragY;
    private boolean moving;

    private KuboWindow(Stage stage, Node content, boolean full) {
        this.stage = stage;
        getStyleClass().add("kubo-window");
        setTop(titleBar(full));
        setContent(content);
        installResizeEdges();
    }

    /**
     * Installs the custom frame. Must be called before the stage is shown.
     */
    public static KuboWindow install(Stage stage, Node content) {
        return install(stage, content, true);
    }

    /**
     * The same frame with one button on it.
     *
     * <p>For a window that is watching something rather than holding a screen: it is opened to be read and
     * closed when it has been. Minimise and maximise on a panel like that are two more things to decide about
     * and neither is ever wanted. It still moves by its bar and still resizes by its edges.
     */
    public static KuboWindow installBare(Stage stage, Node content) {
        return install(stage, content, false);
    }

    private static KuboWindow install(Stage stage, Node content, boolean full) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(content, "content");
        stage.initStyle(StageStyle.UNDECORATED);
        AppIcons.applyTo(stage);
        return new KuboWindow(stage, content, full);
    }

    public void setContent(Node content) {
        setCenter(Objects.requireNonNull(content, "content"));
    }

    private HBox titleBar(boolean full) {
        ImageView icon = new ImageView(AppIcons.mark());
        icon.setFitWidth(16);
        icon.setFitHeight(16);
        icon.setPreserveRatio(true);

        Label mark = new Label(com.kubo.privilege.RootSession.wordmark());
        mark.getStyleClass().add("window-mark");
        com.kubo.privilege.RootSession.elevatedProperty().addListener((observable, before, value) -> {
            mark.setText(com.kubo.privilege.RootSession.wordmark());
            mark.getStyleClass().remove("window-mark-root");
            if (value) {
                mark.getStyleClass().add("window-mark-root");
            }
        });
        Label title = new Label();
        title.textProperty().bind(Bindings.createStringBinding(() -> windowTitle(stage.getTitle()), stage.titleProperty()));
        title.getStyleClass().add("window-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button minimize = control("—", "MINIMIZE");
        Button close = control("×", "CLOSE");
        close.getStyleClass().add("window-close");
        minimize.setOnAction(event -> stage.setIconified(true));
        maximize.setOnAction(event -> toggleMaximized());
        close.setOnAction(event -> stage.close());
        stage.maximizedProperty().addListener((observable, previous, value) -> maximize.setText(value ? "❐" : "□"));

        HBox bar = full
                ? new HBox(10, icon, mark, title, spacer, minimize, maximize, close)
                : new HBox(10, icon, mark, title, spacer, close);
        bar.getStyleClass().add("window-title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        installMove(bar);
        return bar;
    }

    private static Button control(String text, String accessibleText) {
        Button button = new Button(text);
        button.getStyleClass().add("window-control");
        button.setAccessibleText(accessibleText);
        button.setFocusTraversable(false);
        return button;
    }

    private static String windowTitle(String title) {
        if (title == null || title.isBlank() || title.equals("Kubo")) {
            return "SSH SESSION";
        }
        return title.startsWith("Kubo — ") ? title.substring("Kubo — ".length()) : title;
    }

    private void toggleMaximized() {
        stage.setMaximized(!stage.isMaximized());
    }

    private void installMove(HBox bar) {
        bar.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || isWindowControl(event.getTarget())) {
                return;
            }
            moving = true;
            dragX = event.getScreenX() - stage.getX();
            dragY = event.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(event -> {
            if (!moving || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (stage.isMaximized()) {
                double ratio = event.getSceneX() / Math.max(1, stage.getWidth());
                stage.setMaximized(false);
                stage.setX(event.getScreenX() - stage.getWidth() * ratio);
                stage.setY(Math.max(0, event.getScreenY() - 17));
                dragX = event.getScreenX() - stage.getX();
                dragY = event.getScreenY() - stage.getY();
            }
            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        });
        bar.setOnMouseReleased(event -> moving = false);
        bar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && !isWindowControl(event.getTarget())) {
                toggleMaximized();
            }
        });
    }

    private static boolean isWindowControl(Object target) {
        Node node = target instanceof Node value ? value : null;
        while (node != null) {
            if (node.getStyleClass().contains("window-control")) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void installResizeEdges() {
        addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (!stage.isMaximized() && resizing == 0) {
                int edge = edgeAt(event.getSceneX(), event.getSceneY());
                setCursor(cursorFor(edge));
            }
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || stage.isMaximized()
                    || isWindowControl(event.getTarget())) {
                return;
            }
            resizing = edgeAt(event.getSceneX(), event.getSceneY());
            if (resizing == 0) {
                return;
            }
            pressedX = event.getScreenX();
            pressedY = event.getScreenY();
            originalX = stage.getX();
            originalY = stage.getY();
            originalWidth = stage.getWidth();
            originalHeight = stage.getHeight();
            event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (resizing == 0) {
                return;
            }
            resizeStage(event.getScreenX() - pressedX, event.getScreenY() - pressedY);
            event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_RELEASED, event -> resizing = 0);
    }

    private int edgeAt(double x, double y) {
        int edge = 0;
        if (x <= EDGE) {
            edge |= LEFT;
        } else if (x >= getWidth() - EDGE) {
            edge |= RIGHT;
        }
        if (y <= EDGE) {
            edge |= TOP;
        } else if (y >= getHeight() - EDGE) {
            edge |= BOTTOM;
        }
        return edge;
    }

    private void resizeStage(double dx, double dy) {
        double minWidth = Math.max(320, stage.getMinWidth());
        double minHeight = Math.max(240, stage.getMinHeight());
        if ((resizing & LEFT) != 0) {
            double width = Math.max(minWidth, originalWidth - dx);
            stage.setX(originalX + originalWidth - width);
            stage.setWidth(width);
        } else if ((resizing & RIGHT) != 0) {
            stage.setWidth(Math.max(minWidth, originalWidth + dx));
        }
        if ((resizing & TOP) != 0) {
            double height = Math.max(minHeight, originalHeight - dy);
            stage.setY(originalY + originalHeight - height);
            stage.setHeight(height);
        } else if ((resizing & BOTTOM) != 0) {
            stage.setHeight(Math.max(minHeight, originalHeight + dy));
        }
    }

    private static Cursor cursorFor(int edge) {
        return switch (edge) {
            case LEFT -> Cursor.W_RESIZE;
            case RIGHT -> Cursor.E_RESIZE;
            case TOP -> Cursor.N_RESIZE;
            case BOTTOM -> Cursor.S_RESIZE;
            case LEFT | TOP -> Cursor.NW_RESIZE;
            case RIGHT | TOP -> Cursor.NE_RESIZE;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }
}
