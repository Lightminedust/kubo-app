package com.kubo.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.util.List;
import java.util.Objects;

/**
 * Kubo's icon set, shared by every application window.
 */
public final class AppIcons {

    private static final List<Image> ICONS = List.of(
            load("/favicon-16x16.png"),
            load("/favicon-32x32.png"),
            load("/apple-touch-icon.png"),
            load("/android-chrome-192x192.png"),
            load("/android-chrome-512x512.png")
    );

    private AppIcons() {
    }

    /**
     * Lets the platform choose the most suitable resolution for the title bar, taskbar and window switcher.
     */
    public static void applyTo(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        stage.getIcons().setAll(ICONS);
    }

    /**
     * The compact mark used in Kubo's own title bar.
     */
    public static Image mark() {
        return ICONS.get(1);
    }

    private static Image load(String path) {
        URL resource = Objects.requireNonNull(AppIcons.class.getResource(path), "Missing application icon: " + path);
        return new Image(resource.toExternalForm());
    }
}
