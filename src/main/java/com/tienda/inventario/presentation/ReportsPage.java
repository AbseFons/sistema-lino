package com.tienda.inventario.presentation;

import com.tienda.inventario.application.ReportsService;
import com.tienda.inventario.application.ReportExportService;
import com.tienda.inventario.application.FullExcelReportService;
import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.application.DemandService;
import com.tienda.inventario.application.DecimalUnits;
import com.tienda.inventario.domain.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** Reportes de venta operativos, no informes tributarios ni utilidades netas. */
public final class ReportsPage {
    private final ReportsService reports;
    private final ReportExportService exporter;
    private final FullExcelReportService excel;
    private final boolean admin;
    private final Stage owner;
    public ReportsPage(ReportsService reports, DemandService demand,Database db,boolean admin,Stage owner){
        this.reports=reports;this.exporter=new ReportExportService(reports,demand);
        this.excel=new FullExcelReportService(db);this.admin=admin;this.owner=owner;
    }

    public Node build(){
        DatePicker from=new DatePicker(LocalDate.now(ReportsService.BUSINESS_ZONE).withDayOfMonth(1));
        DatePicker to=new DatePicker(LocalDate.now(ReportsService.BUSINESS_ZONE));
        Button update=new Button("Actualizar reportes");update.getStyleClass().add("primary-button");
        ComboBox<Integer> idlePeriod=new ComboBox<>(FXCollections.observableArrayList(30,60,90,180));
        idlePeriod.setValue(90);idlePeriod.setPrefWidth(115);

        VBox metrics=new VBox();
        TableView<ProductSales> tops=new TableView<>();tops.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tops.getColumns().addAll(col("SKU",ProductSales::sku),col("Producto",ProductSales::name),
                col("Cantidad (unidad base)",r->DecimalUnits.displayMilli(r.quantityBaseMilli())+" "+r.unit()),
                col("Ventas",r->Long.toString(r.transactions())),col("Facturación",r->soles(r.revenueCents())));
        tops.setPrefHeight(265);
        TableView<SalesByPayment> payments=new TableView<>();payments.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        payments.getColumns().addAll(col("Medio",SalesByPayment::method),col("Operaciones de pago",r->Long.toString(r.paymentCount())),
                col("Total recaudado",r->soles(r.amountCents())));payments.setPrefHeight(195);
        TableView<IdleProduct> idle=new TableView<>();idle.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        idle.getColumns().addAll(col("SKU",IdleProduct::sku),col("Producto",IdleProduct::name),
                col("Stock actual",r->DecimalUnits.displayMilli(r.stockMilli())+" "+r.unit()),
                col("Última venta",r->r.lastSaleAt()==null?"Sin ventas registradas":r.lastSaleAt().substring(0,10)));
        idle.setPrefHeight(250);
        CategoryAxis xAxis=new CategoryAxis();xAxis.setLabel("Día comercial (Perú)");
        NumberAxis yAxis=new NumberAxis();yAxis.setLabel("Soles vendidos");
        BarChart<String,Number> dailyChart=new BarChart<>(xAxis,yAxis);
        dailyChart.setLegendVisible(false);dailyChart.setAnimated(false);dailyChart.setPrefHeight(310);
        Label chartNote=small("Se visualizan los últimos 30 días con ventas del período seleccionado. Días sin ventas no aparecen.");

        Runnable refresh=()->{
            try{
                if(from.getValue()==null||to.getValue()==null||from.getValue().isAfter(to.getValue()))
                    throw new IllegalArgumentException("Selecciona fechas válidas; inicio no puede superar fin");
                SalesSummary s=reports.summary(from.getValue(),to.getValue());
                HBox row=new HBox(15,card("Ventas confirmadas",Long.toString(s.sales())),
                        card("Facturación del período",soles(s.revenueCents())),
                        card("Ticket promedio",soles(s.averageTicketCents())),
                        card("Descuentos",soles(s.discountsCents())));
                for(Node n:row.getChildren())HBox.setHgrow(n,Priority.ALWAYS);
                metrics.getChildren().setAll(row);
                tops.setItems(FXCollections.observableArrayList(reports.topProducts(from.getValue(),to.getValue(),30)));
                payments.setItems(FXCollections.observableArrayList(reports.paymentMix(from.getValue(),to.getValue())));
                List<SalesByDay> days=reports.daily(from.getValue(),to.getValue());
                XYChart.Series<String,Number> s1=new XYChart.Series<>();
                int start=Math.max(0,days.size()-30);
                for(int i=start;i<days.size();i++){
                    SalesByDay day=days.get(i);
                    s1.getData().add(new XYChart.Data<>(day.date().substring(5),day.revenueCents()/100.0));
                }
                dailyChart.getData().setAll(s1);
                idle.setItems(FXCollections.observableArrayList(reports.inactiveProducts(idlePeriod.getValue(),200)));
            }catch(Exception ex){error("No se pudieron cargar reportes",ex);}
        };
        update.setOnAction(e->refresh.run());idlePeriod.setOnAction(e->refresh.run());
        ComboBox<ReportExportService.Type> exportType=new ComboBox<>(
                FXCollections.observableArrayList(ReportExportService.Type.values()));
        exportType.setValue(ReportExportService.Type.DIARIAS);
        Button exportButton=new Button("Exportar CSV");exportButton.setDisable(!admin);
        exportButton.setOnAction(e->{
            try {
                FileChooser chooser=new FileChooser();
                chooser.setTitle("Exportar reporte comercial a CSV");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV para Excel","*.csv"));
                chooser.setInitialFileName("reporte_"+LocalDate.now(ReportsService.BUSINESS_ZONE)+".csv");
                File file=chooser.showSaveDialog(owner);
                if(file==null)return;
                exporter.export(exportType.getValue(),from.getValue(),to.getValue(),idlePeriod.getValue(),file.toPath());
                new Alert(Alert.AlertType.INFORMATION,"Reporte exportado correctamente:\n"+file.getAbsolutePath(),ButtonType.OK).showAndWait();
            } catch(Exception ex){error("No fue posible exportar el CSV",ex);}
        });
        Button exportExcel=new Button("Exportar Excel completo (.xlsx)");
        exportExcel.getStyleClass().add("primary-button");
        exportExcel.setDisable(!admin);
        exportExcel.setOnAction(e->{
            try {
                FileChooser chooser=new FileChooser();
                chooser.setTitle("Guardar informe comercial completo en Excel");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Libro Excel (*.xlsx)","*.xlsx"));
                chooser.setInitialFileName("informe_comercial_"+LocalDate.now(ReportsService.BUSINESS_ZONE)+".xlsx");
                File file=chooser.showSaveDialog(owner);
                if(file==null)return;
                excel.export(file.toPath(),from.getValue(),to.getValue(),idlePeriod.getValue());
                new Alert(Alert.AlertType.INFORMATION,
                        "Informe Excel creado (9 hojas):\n"+file.getAbsolutePath(),ButtonType.OK).showAndWait();
            }catch(Exception ex){error("No fue posible exportar el informe Excel",ex);}
        });
        HBox csvBar=new HBox(12,field("Tipo de reporte CSV",exportType),exportButton);
        csvBar.setAlignment(Pos.BOTTOM_LEFT);
        VBox exportBar=new VBox(10,csvBar,exportExcel);
        exportBar.setAlignment(Pos.CENTER_LEFT);
        HBox filter=new HBox(12,field("Desde",from),field("Hasta (inclusive)",to),update);
        filter.setAlignment(Pos.BOTTOM_LEFT);
        Label warning=small("Cifras OPERATIVAS de ventas confirmadas. No son CPE emitidos ni declaraciones SUNAT. " +
                "No calculamos utilidad real mientras falte validar impuestos y costos de compra.");
        warning.setWrapText(true);
        HBox inactiveHeader=new HBox(12,title("Productos sin venta reciente"),small("Días sin venta:"),idlePeriod);
        inactiveHeader.setAlignment(Pos.CENTER_LEFT);
        VBox content=new VBox(16,title("Reportes comerciales"),
                small("Períodos calculados con horario de Perú (America/Lima); la facturación se basa en ventas confirmadas."),
                filter,exportBar,small("Solo administrador. CSV = una tabla. Excel completo = 9 hojas; inventario y solicitudes muestran su estado actual/histórico sin filtro de ventas."),metrics,warning,title("Evolución de ventas"),dailyChart,chartNote,
                title("Productos más vendidos por facturación"),tops,
                title("Cobros por medio de pago"),payments,
                inactiveHeader,small("Solo productos ACTIVOS con stock positivo y creados antes del plazo elegido."),idle);
        content.setPadding(new Insets(28));
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.getStyleClass().add("report-scroll");
        refresh.run();return scroll;
    }
    private static VBox field(String text,Node field){return new VBox(5,small(text),field);}
    private static VBox card(String key,String value){
        Label big=new Label(value);big.getStyleClass().add("card-number");
        VBox b=new VBox(10,small(key),big);b.getStyleClass().add("summary-card");b.setMaxWidth(Double.MAX_VALUE);return b;
    }
    private static String soles(long cents){return "S/ "+BigDecimal.valueOf(cents,2).toPlainString();}
    private static Label small(String v){Label l=new Label(v);l.getStyleClass().add("muted");return l;}
    private static Label title(String v){Label l=new Label(v);l.getStyleClass().add("section-heading");return l;}
    private static <T> TableColumn<T,String> col(String label,Function<T,String> f){
        TableColumn<T,String> c=new TableColumn<>(label);
        c.setCellValueFactory(r->new ReadOnlyStringWrapper(f.apply(r.getValue())));return c;
    }
    private static void error(String name,Exception e){
        new Alert(Alert.AlertType.ERROR,name+": "+e.getMessage(),ButtonType.OK).showAndWait();
    }
}
