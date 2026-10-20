package com.tienda.inventario.presentation;

import com.tienda.inventario.infrastructure.database.BackupService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Consumer;

/** Operaciones administrativas críticas. La restauración solo se hace al arranque. */
public final class BackupPage {
    private final BackupService backups;
    private final Stage owner;
    private final Runnable closeAndRestart;
    private final String startupWarning;

    public BackupPage(BackupService backups, Stage owner, Runnable closeAndRestart, String startupWarning) {
        this.backups=backups;this.owner=owner;this.closeAndRestart=closeAndRestart;this.startupWarning=startupWarning;
    }
    public Node build() {
        Label header=new Label("Respaldo y recuperación");header.getStyleClass().add("screen-title");
        Label description=new Label("Resguarda usuarios, productos, compras, ventas y movimientos del inventario. " +
                "Las copias diarias se guardan en la misma computadora; exporta copias periódicamente a una unidad externa.");
        description.setWrapText(true);description.getStyleClass().add("muted");
        Label directory=new Label("Carpeta automática: " + backups.backupsDirectory());directory.setWrapText(true);
        directory.getStyleClass().add("muted");
        Label status=new Label(startupWarning == null ? "Respaldo automático diario habilitado; se conservan los últimos 30." :
                "ATENCIÓN: falló el respaldo automático al iniciar: " + startupWarning);
        status.setWrapText(true);
        Button save=new Button("Crear copia ahora (elegir carpeta)");save.getStyleClass().add("primary-button");
        Button restore=new Button("Restaurar desde un archivo .sqlite");
        HBox actions=new HBox(15,save,restore);actions.setAlignment(Pos.CENTER_LEFT);
        save.setOnAction(e->{
            FileChooser dialog=new FileChooser();dialog.setTitle("Guardar copia de seguridad SQLite");
            dialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base SQLite", "*.sqlite"));
            dialog.setInitialFileName("inventario_respaldo_"+
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+".sqlite");
            File selected=dialog.showSaveDialog(owner);
            if(selected==null)return;
            if(Files.exists(selected.toPath())) { error("Ese nombre ya existe; elige otro para no sobrescribir un respaldo.");return; }
            work(save,restore,status,()->backups.createManualBackup(selected.toPath()),
                    path->info("Respaldo guardado y verificado en:\n"+path));
        });
        restore.setOnAction(e->{
            if(backups.hasPendingRestore()){error("Ya se seleccionó una restauración. Cierra y abre nuevamente la aplicación.");return;}
            FileChooser dialog=new FileChooser();dialog.setTitle("Seleccionar un respaldo SQLite válido");
            dialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base SQLite", "*.sqlite"));
            File chosen=dialog.showOpenDialog(owner);if(chosen==null)return;
            Alert warning=new Alert(Alert.AlertType.WARNING,
                    "RESTAURAR reemplazará la base actual completa: también cambiarán usuarios, contraseñas, " +
                    "productos, compras y ventas posteriores a la fecha del respaldo.\n\n"+
                    "El programa guardará una copia adicional del estado actual antes de restaurar. " +
                    "Se necesita cerrar y volver a abrir la aplicación. ¿Continuar?",ButtonType.YES,ButtonType.NO);
            warning.setTitle("Confirmación de restauración");warning.setHeaderText("Se sustituirán los datos actuales");
            if(warning.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES)return;
            TextInputDialog confirmation=new TextInputDialog();
            confirmation.setTitle("Confirmar restauración");confirmation.setHeaderText("Escribe RESTAURAR para continuar");
            confirmation.setContentText("Confirmación:");
            Optional<String> typed=confirmation.showAndWait();
            if(typed.isEmpty() || !"RESTAURAR".equals(typed.get().trim()))return;
            work(save,restore,status,()->{
                backups.stageRestore(chosen.toPath());return backups.pendingRestorePath();
            },path->{
                info("Se validó el respaldo. La restauración se aplicará al próximo inicio.\n"+
                        "Cierra la aplicación y ábrela nuevamente para recuperar los datos.");
                closeAndRestart.run();
            });
        });
        Label warning=new Label("IMPORTANTE: no copies el archivo inventario.sqlite mientras está abierto; " +
                "usa Crear copia ahora. No guardes la única copia en el mismo disco de la tienda. " +
                "No restaurar durante una operación de venta.");
        warning.setWrapText(true);warning.getStyleClass().add("muted");
        VBox content=new VBox(18,header,description,directory,status,actions,warning);
        content.setPadding(new Insets(34));return content;
    }
    private static <T> void work(Button save, Button restore, Label status,
                                  ThrowingSupplier<T> supplier,Consumer<T> success) {
        save.setDisable(true);restore.setDisable(true);
        status.setText("Procesando... no cierres la aplicación hasta terminar.");
        Task<T> task=new Task<>() { @Override protected T call() throws Exception {return supplier.get();} };
        task.setOnSucceeded(e->{save.setDisable(false);restore.setDisable(false);
            status.setText("Operación completada.");success.accept(task.getValue());});
        task.setOnFailed(e->{save.setDisable(false);restore.setDisable(false);
            status.setText("No se pudo completar la operación.");
            Throwable why=task.getException();error(why==null?"Error desconocido":why.getMessage());});
        Thread thread=new Thread(task,"respaldo-inventario");thread.setDaemon(true);thread.start();
    }
    @FunctionalInterface private interface ThrowingSupplier<T>{T get()throws Exception;}
    private static void error(String message){new Alert(Alert.AlertType.ERROR,message,ButtonType.OK).showAndWait();}
    private static void info(String message){new Alert(Alert.AlertType.INFORMATION,message,ButtonType.OK).showAndWait();}
}
