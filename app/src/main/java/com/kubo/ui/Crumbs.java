package com.kubo.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where you are, as a row of the folders above it, each one a way back.
 *
 * <p>For a server path, which always starts at {@code /}. Every crumb but the last is a button that goes back
 * there; the last one is where you are and does nothing.
 */
public final class Crumbs extends FlowPane {

    private final Consumer<String> onPick;

    /**
     * @param onPick called with the full path of the crumb clicked
     */
    public Crumbs(Consumer<String> onPick) {
        super(4, 2);
        this.onPick = onPick;
        setPrefWrapLength(380);
    }

    /** Shows this path. */
    public void show(String path) {
        getChildren().clear();
        List<String> chain = chain(path);
        for (int i = 0; i < chain.size(); i++) {
            String step = chain.get(i);
            boolean last = i == chain.size() - 1;
            Button crumb = new Button(name(step));
            crumb.getStyleClass().add(last ? "spine-crumb-current" : "spine-crumb");
            crumb.setFocusTraversable(false);
            if (!last) {
                crumb.setOnAction(event -> onPick.accept(step));
            }
            getChildren().add(crumb);
            if (!last) {
                Label between = new Label("›");
                between.getStyleClass().add("mono-muted");
                getChildren().add(between);
            }
        }
    }

    /** "/var/www/site" as "/", "/var", "/var/www", "/var/www/site". */
    public static List<String> chain(String path) {
        List<String> chain = new ArrayList<>();
        chain.add("/");
        if (path == null || path.isBlank() || path.equals("/")) {
            return chain;
        }
        StringBuilder built = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            built.append('/').append(part);
            chain.add(built.toString());
        }
        return chain;
    }

    /** The last part of a path, or "/" for the root. */
    public static String name(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "/";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /** The folder above, or "/" for anything directly under the root. */
    public static String parent(String path) {
        List<String> chain = chain(path);
        return chain.size() < 2 ? "/" : chain.get(chain.size() - 2);
    }
}
