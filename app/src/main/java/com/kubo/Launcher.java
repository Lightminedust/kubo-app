package com.kubo;

import javafx.application.Application;

/**
 * Plain entry point: launching an Application subclass directly from a jar
 * fails with "JavaFX runtime components are missing".
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(KuboApp.class, args);
    }
}
