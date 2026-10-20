package com.tienda.inventario.app;

import javafx.application.Application;

/** Entry point kept separate from Application for standard Gradle classpath startup. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Application.launch(InventarioApp.class, args);
    }
}
