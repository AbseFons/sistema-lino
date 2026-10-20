package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exportaciones operativas CSV para Excel en español (UTF-8 BOM, punto y coma y coma decimal). */
public final class ReportExportService {
    public enum Type {
        RESUMEN("Resumen comercial"), DIARIAS("Ventas por día"), PRODUCTOS("Productos más vendidos"),
        PAGOS("Cobros por medio de pago"), INACTIVOS("Productos sin venta reciente"),
        SOLICITUDES("Solicitudes pendientes más repetidas");
        private final String label;
        Type(String label) { this.label=label; }
        @Override public String toString(){return label;}
    }
    private final ReportsService reports;
    private final DemandService demand;
    public ReportExportService(ReportsService reports, DemandService demand) {
        this.reports=reports; this.demand=demand;
    }
    public Path export(Type type, LocalDate from, LocalDate to, int idleDays, Path file) throws SQLException, IOException {
        if (type == null || file == null) throw new IllegalArgumentException("Selecciona reporte y ruta");
        // En todos los casos validamos fechas para evitar filtros inconsistentes en la interfaz.
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("Fechas inválidas");
        List<List<String>> rows=new ArrayList<>();
        switch(type) {
            case RESUMEN -> {
                rows.add(List.of("desde","hasta","ventas_confirmadas","total_soles","descuentos_soles","ticket_promedio_soles"));
                SalesSummary s=reports.summary(from,to);
                rows.add(List.of(from.toString(),to.toString(),Long.toString(s.sales()),money(s.revenueCents()),
                        money(s.discountsCents()),money(s.averageTicketCents())));
            }
            case DIARIAS -> {
                rows.add(List.of("dia_peru","ventas_confirmadas","total_soles"));
                for (SalesByDay s:reports.daily(from,to)) rows.add(List.of(s.date(),Long.toString(s.sales()),money(s.revenueCents())));
            }
            case PRODUCTOS -> {
                rows.add(List.of("sku","producto","unidad_base","cantidad_en_unidad_base","ventas","total_soles"));
                for (ProductSales s:reports.topProducts(from,to,100)) rows.add(List.of(
                        s.sku(),s.name(),s.unit(),quantity(s.quantityBaseMilli()),Long.toString(s.transactions()),money(s.revenueCents())));
            }
            case PAGOS -> {
                rows.add(List.of("medio_de_pago","operaciones_de_pago","total_soles"));
                for (SalesByPayment s:reports.paymentMix(from,to)) rows.add(List.of(
                        s.method(),Long.toString(s.paymentCount()),money(s.amountCents())));
            }
            case INACTIVOS -> {
                rows.add(List.of("sku","producto","unidad_base","stock_actual","ultima_venta_utc","dias_sin_venta"));
                for (IdleProduct s:reports.inactiveProducts(idleDays,1000)) rows.add(List.of(
                        s.sku(),s.name(),s.unit(),quantity(s.stockMilli()),nullToEmpty(s.lastSaleAt()),Integer.toString(idleDays)));
            }
            case SOLICITUDES -> {
                rows.add(List.of("producto_solicitado","marca_modelo","numero_solicitudes","cantidad_conocida"));
                for (DemandRanking s:demand.mostRequested(100)) rows.add(List.of(
                        s.description(),nullToEmpty(s.brandModel()),Long.toString(s.requests()),quantity(s.quantityKnownMilli())));
            }
        }
        Path destination=file.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temp=destination.resolveSibling("."+destination.getFileName()+"."+UUID.randomUUID()+".tmp");
        try {
            try (BufferedWriter writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write('\ufeff'); // Excel detecta UTF-8, inclusive con tildes
                for (List<String> row:rows) {
                    for (int i=0;i<row.size();i++) {
                        if (i>0)writer.write(';');
                        writer.write(csvField(row.get(i)));
                    }
                    writer.write("\r\n");
                }
            }
            try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING); }
            return destination;
        } finally {Files.deleteIfExists(temp);}
    }
    static String csvField(String input) {
        String value=nullToEmpty(input);
        // Evita ejecución de fórmulas al abrir CSV de nombres/SKU ingresados por terceros en Excel.
        String leading=value.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0) value="'"+value;
        if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static String money(long cents) {return BigDecimal.valueOf(cents,2).toPlainString().replace('.',',');}
    private static String quantity(long milli) {return BigDecimal.valueOf(milli,3).stripTrailingZeros().toPlainString().replace('.',',');}
    private static String nullToEmpty(String text) {return text==null?"":text;}
}
