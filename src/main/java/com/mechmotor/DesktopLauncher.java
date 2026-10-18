package com.mechmotor;

import javafx.application.Application;

/**
 * Stable entry point for jpackage's native Windows launcher.
 * Keeping this separate from the JavaFX Application subclass avoids relying
 * on the Java launcher special-case for JavaFX main classes.
 */
public final class DesktopLauncher {
    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(MechMotorApplication.class, args);
    }
}
