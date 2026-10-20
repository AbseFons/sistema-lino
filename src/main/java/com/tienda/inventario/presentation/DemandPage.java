package com.tienda.inventario.presentation;

import com.tienda.inventario.application.DemandService;
import com.tienda.inventario.application.DecimalUnits;
import com.tienda.inventario.domain.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.math.BigDecimal;
import java.util.function.Function;

/** Oportunidades de venta perdida: capturables por cualquier trabajador activo. */
public final class DemandPage {
    private final DemandService demands;
    private final Session session;
    private final Stage owner;
    private final String stylesheet;
    public DemandPage(DemandService demands,Session session,Stage owner,String stylesheet){
        this.demands=demands;this.session=session;this.owner=owner;this.stylesheet=stylesheet;
    }
    public Node build(){
        Label total=small("");
        TextField filter=new TextField();filter.setPromptText("Buscar producto o marca/modelo");filter.setPrefWidth(275);
        CheckBox pendingOnly=new CheckBox("Solo pendientes");pendingOnly.setSelected(true);
        TableView<UnmetDemand> table=new TableView<>();table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(col("N.°",r->Long.toString(r.id())),
                col("Fecha",r->r.date().substring(0,10)),col("Producto solicitado",UnmetDemand::description),
                col("Marca / modelo",r->nonNull(r.brandModel())),col("Cantidad",r->r.quantityMilli()==null?"—":DecimalUnits.displayMilli(r.quantityMilli())),
                col("Trabajador",UnmetDemand::worker),col("Estado",UnmetDemand::status));
        table.setPlaceholder(small("Todavía no hay solicitudes registradas"));table.setPrefHeight(350);
        TableView<DemandRanking> ranking=new TableView<>();ranking.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        ranking.getColumns().addAll(col("Producto",DemandRanking::description),col("Marca / modelo",r->nonNull(r.brandModel())),
                col("Veces solicitado",r->Long.toString(r.requests())),
                col("Cantidad conocida",r->DecimalUnits.displayMilli(r.quantityKnownMilli())));
        ranking.setPrefHeight(220);
        Runnable refresh=()->{try{
            table.setItems(FXCollections.observableArrayList(demands.findAll(pendingOnly.isSelected(),filter.getText(),500)));
            ranking.setItems(FXCollections.observableArrayList(demands.mostRequested(25)));
            total.setText("Solicitudes pendientes: "+demands.countPending());
        }catch(Exception ex){error("No se pudieron cargar solicitudes",ex);}};
        Button add=new Button("+ Registrar solicitud");add.getStyleClass().add("primary-button");
        Button attend=new Button("Marcar atendida"),discard=new Button("Descartar"),details=new Button("Ver detalle");
        discard.setDisable(!session.isAdmin());
        attend.setOnAction(e->resolveSelected(table,"ATENDIDA",refresh));
        discard.setOnAction(e->resolveSelected(table,"DESCARTADA",refresh));
        details.setOnAction(e->{UnmetDemand entry=table.getSelectionModel().getSelectedItem();if(entry!=null)
            new Alert(Alert.AlertType.INFORMATION,"Producto: "+entry.description()+"\nMarca/modelo: "+nonNull(entry.brandModel())+
                    "\nSolicitado por: "+entry.worker()+"\nCliente: "+nonNull(entry.customer())+
                    "\nSKU vinculado: "+nonNull(entry.sku())+"\nObservaciones: "+nonNull(entry.notes()),ButtonType.OK).showAndWait();});
        add.setOnAction(e->newRequest(refresh));
        Button reload=new Button("Actualizar");reload.setOnAction(e->refresh.run());
        filter.setOnAction(e->refresh.run());pendingOnly.setOnAction(e->refresh.run());
        HBox toolbar=new HBox(12,add,attend,discard,details,reload);toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox options=new HBox(12,filter,pendingOnly,total);options.setAlignment(Pos.CENTER_LEFT);
        VBox content=new VBox(16,title("Solicitudes no atendidas"),
                small("Registra lo que el cliente pidió y no estaba disponible, aunque ese producto todavía no exista en el catálogo."),
                toolbar,options,table,title("Productos más solicitados (pendientes)"),ranking,
                small("Las solicitudes resueltas permanecen en el historial. Marcar atendida NO genera venta ni modifica stock."));
        content.setPadding(new Insets(28));
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);refresh.run();return scroll;
    }

    private void newRequest(Runnable refreshed){
        Stage dlg=dialog("Nueva solicitud no atendida");
        TextField name=new TextField(),brand=new TextField(),qty=new TextField();
        TextArea notes=new TextArea();notes.setPrefRowCount(3);
        name.setPromptText("Ej. Filtro de aire modelo XYZ");
        brand.setPromptText("Marca, edición, compatibilidad...");
        qty.setPromptText("Opcional; ej. 2");
        Label explain=small("La cantidad es referencial. Especifica unidades/presentación en observaciones si corresponde.");explain.setWrapText(true);
        Button save=new Button("Guardar solicitud");save.getStyleClass().add("primary-button");
        Button cancel=new Button("Cancelar");cancel.setOnAction(e->dlg.close());
        save.setOnAction(e->{try{
            BigDecimal amount=qty.getText().isBlank()?null:new BigDecimal(qty.getText().trim().replace(',','.'));
            demands.register(name.getText(),brand.getText(),amount,null,null,notes.getText(),session);
            dlg.close();refreshed.run();
        }catch(Exception ex){error("No se pudo registrar la solicitud",ex);}});
        VBox content=new VBox(13,title("Nueva solicitud"),field("Producto solicitado *",name),
                field("Marca / modelo",brand),field("Cantidad solicitada",qty),explain,
                field("Observaciones",notes),new HBox(12,cancel,save));content.setPadding(new Insets(26));
        dlg.setScene(scene(content,620,535));dlg.showAndWait();
    }

    private void resolveSelected(TableView<UnmetDemand> table,String status,Runnable refreshed){
        UnmetDemand selected=table.getSelectionModel().getSelectedItem();
        if(selected==null){new Alert(Alert.AlertType.INFORMATION,"Selecciona una solicitud",ButtonType.OK).showAndWait();return;}
        if(!"PENDIENTE".equals(selected.status())){new Alert(Alert.AlertType.WARNING,"La solicitud ya está resuelta",ButtonType.OK).showAndWait();return;}
        TextInputDialog reason=new TextInputDialog();reason.initOwner(owner);
        reason.setTitle("Resolver solicitud #"+selected.id());reason.setHeaderText("Marcar como "+status+": "+selected.description());
        reason.setContentText("Motivo o referencia:");
        reason.showAndWait().ifPresent(text->{try{
            demands.resolve(selected.id(),status,text,session);refreshed.run();
        }catch(Exception ex){error("No se pudo cambiar el estado",ex);}});
    }
    private Stage dialog(String name){Stage stage=new Stage();stage.setTitle(name);stage.initOwner(owner);stage.initModality(Modality.WINDOW_MODAL);return stage;}
    private Scene scene(Pane p,int w,int h){Scene s=new Scene(p,w,h);s.getStylesheets().add(stylesheet);return s;}
    private static VBox field(String title,Node input){return new VBox(6,small(title),input);}
    private static String nonNull(String s){return s==null||s.isBlank()?"—":s;}
    private static Label small(String s){Label l=new Label(s);l.getStyleClass().add("muted");return l;}
    private static Label title(String s){Label l=new Label(s);l.getStyleClass().add("section-heading");return l;}
    private static <T> TableColumn<T,String> col(String label,Function<T,String> f){
        TableColumn<T,String> c=new TableColumn<>(label);
        c.setCellValueFactory(row->new ReadOnlyStringWrapper(f.apply(row.getValue())));return c;
    }
    private static void error(String name,Exception ex){new Alert(Alert.AlertType.ERROR,name+": "+ex.getMessage(),ButtonType.OK).showAndWait();}
}
