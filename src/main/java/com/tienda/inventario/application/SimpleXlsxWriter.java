package com.tienda.inventario.application;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escritor XLSX (OOXML) secuencial sin dependencias adicionales.
 * Las cadenas se escriben como inlineStr: nombres externos que empiecen por =,+,-,@
 * nunca se convierten en fórmulas. Las cifras se escriben como valores numéricos.
 * Cada hoja se construye por filas, sin acumular toda la base en RAM.
 */
public final class SimpleXlsxWriter implements AutoCloseable {
    private static final String SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private final ZipOutputStream zip;
    private final List<String> sheetNames = new ArrayList<>();
    private boolean closed;

    public record Cell(String text, Long number, Kind kind) {
        public enum Kind { TEXT, MONEY, QUANTITY, COUNT, PRICE }
        public static Cell text(String s) { return new Cell(s == null ? "" : s, null, Kind.TEXT); }
        public static Cell money(long cents) { return new Cell(null, cents, Kind.MONEY); }
        public static Cell micro(long micro) { return new Cell(null, micro, Kind.PRICE); }
        public static Cell qty(long milli) { return new Cell(null, milli, Kind.QUANTITY); }
        public static Cell count(long n) { return new Cell(null, n, Kind.COUNT); }
        public static Cell blank() { return text(""); }
    }

    public SimpleXlsxWriter(OutputStream output) {
        this.zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
    }

    public void addSheet(String name, String description, String period, String[] headers,
                         int[] widths, SheetContent rows) throws Exception {
        if (closed) throw new IllegalStateException("Libro ya cerrado");
        if (name == null || name.isBlank() || name.length() > 31 || name.matches(".*[\\\\/?*\\[\\]:].*"))
            throw new IllegalArgumentException("Nombre de hoja no válido");
        if (sheetNames.contains(name)) throw new IllegalArgumentException("Hoja repetida: " + name);
        if (headers.length == 0 || headers.length != widths.length) throw new IllegalArgumentException("Columnas incorrectas");
        sheetNames.add(name);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheetNames.size() + ".xml"));
        Writer out = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        out.write("<worksheet xmlns=\"" + SS + "\"><sheetViews><sheetView workbookViewId=\"0\">");
        out.write("<pane ySplit=\"5\" topLeftCell=\"A6\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        out.write("<selection pane=\"bottomLeft\" activeCell=\"A6\" sqref=\"A6\"/>");
        out.write("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for (int i=0;i<widths.length;i++) {
            out.write("<col min=\"" + (i+1) + "\" max=\"" + (i+1) + "\" width=\"" + Math.max(12,Math.min(55,widths[i])) + "\" customWidth=\"1\"/>");
        }
        out.write("</cols><sheetData>");
        rowOpen(out,1);cellXml(out,1,1,Cell.text(name),5);rowClose(out);
        rowOpen(out,2);cellXml(out,2,1,Cell.text(description),0);rowClose(out);
        rowOpen(out,3);cellXml(out,3,1,Cell.text(period),0);rowClose(out);
        rowOpen(out,5);
        for(int i=0;i<headers.length;i++)cellXml(out,5,i+1,Cell.text(headers[i]),4);
        rowClose(out);
        Sheet sheet = new Sheet(out,headers.length);
        rows.write(sheet);
        out.write("</sheetData>");
        out.write("<autoFilter ref=\"A5:"+col(headers.length)+Math.max(6,sheet.lastRow)+"\"/>");
        out.write("<mergeCells count=\"3\"><mergeCell ref=\"A1:"+col(headers.length)+"1\"/>");
        out.write("<mergeCell ref=\"A2:"+col(headers.length)+"2\"/>");
        out.write("<mergeCell ref=\"A3:"+col(headers.length)+"3\"/></mergeCells>");
        out.write("</worksheet>");
        out.flush();zip.closeEntry();
    }

    @FunctionalInterface public interface SheetContent { void write(Sheet sheet) throws Exception; }
    public static final class Sheet {
        private final Writer out;
        private final int columns;
        private int lastRow=5;
        private Sheet(Writer out,int columns){this.out=out;this.columns=columns;}
        public void row(Cell... cells) throws IOException {
            if(cells.length!=columns)throw new IllegalArgumentException("Cantidad de columnas: esperadas "+columns+", recibidas "+cells.length);
            // Excel admite hasta 1,048,576 filas por hoja.
            if(lastRow >= 1_048_576)throw new IOException("Demasiadas filas para Excel");
            int r=++lastRow;
            rowOpen(out,r);
            for(int i=0;i<cells.length;i++)cellXml(out,r,i+1,cells[i]==null?Cell.blank():cells[i], r%2==0?0:1);
            rowClose(out);
        }
    }

    private static void rowOpen(Writer out,int r) throws IOException {out.write("<row r=\""+r+"\">");}
    private static void rowClose(Writer out)throws IOException {out.write("</row>");}
    private static void cellXml(Writer out,int r,int c,Cell cell,int stripe) throws IOException {
        Cell.Kind kind=cell.kind();
        int style = stripe==5 ? 5 : stripe==4 ? 4 : switch(kind) {
            case TEXT -> stripe==1?1:0;
            case MONEY -> stripe==1?8:2;
            case QUANTITY -> stripe==1?9:3;
            case COUNT -> stripe==1?10:6;
            case PRICE -> stripe==1?12:11;
        };
        out.write("<c r=\""+col(c)+r+"\" s=\""+style+"\"");
        if(kind==Cell.Kind.TEXT){
            out.write(" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
            out.write(xml(cell.text()));out.write("</t></is></c>");
        } else {
            out.write(" t=\"n\"><v>");
            if(kind==Cell.Kind.MONEY)out.write(java.math.BigDecimal.valueOf(cell.number(),2).toPlainString());
            else if(kind==Cell.Kind.QUANTITY)out.write(java.math.BigDecimal.valueOf(cell.number(),3).toPlainString());
            else if(kind==Cell.Kind.PRICE)out.write(java.math.BigDecimal.valueOf(cell.number(),4).toPlainString());
            else out.write(Long.toString(cell.number()));
            out.write("</v></c>");
        }
    }
    private static String col(int n) {
        StringBuilder b=new StringBuilder();while(n>0){n--;b.insert(0,(char)('A'+n%26));n/=26;}return b.toString();
    }
    private static String xml(String value){
        StringBuilder b=new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if(cp!=9 && cp!=10 && cp!=13 && (cp<32 || (cp>=0xD800 && cp<=0xDFFF) || cp==0xFFFE || cp==0xFFFF)) return;
            switch(cp){case '&' -> b.append("&amp;");case '<' -> b.append("&lt;");case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");default -> b.appendCodePoint(cp);}
        });return b.toString();
    }
    private void entry(String name,String value)throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Override public void close() throws IOException {
        if(closed)return;
        closed=true;
        try{
            StringBuilder types=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                 .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                 .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                 .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                 .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for(int i=1;i<=sheetNames.size();i++)types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            types.append("</Types>");entry("[Content_Types].xml",types.toString());
            entry("_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            StringBuilder workbook=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\""+SS+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for(int i=1;i<=sheetNames.size();i++){
                workbook.append("<sheet name=\"").append(xml(sheetNames.get(i-1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
            }
            workbook.append("</sheets></workbook>");
            rels.append("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry("xl/workbook.xml",workbook.toString());entry("xl/_rels/workbook.xml.rels",rels.toString());
            entry("xl/styles.xml",styles());
        } finally {zip.close();}
    }
    private static String styles(){
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <numFmts count="3"><numFmt numFmtId="164" formatCode="&quot;S/ &quot;#,##0.00"/>
        <numFmt numFmtId="165" formatCode="#,##0.###"/>
        <numFmt numFmtId="166" formatCode="&quot;S/ &quot;#,##0.0000"/></numFmts>
        <fonts count="3"><font><sz val="11"/><name val="Aptos"/></font>
        <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Aptos"/></font>
        <font><b/><sz val="17"/><color rgb="FFFFFFFF"/><name val="Aptos Display"/></font></fonts>
        <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FFEFF4FA"/><bgColor indexed="64"/></patternFill></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FF17365B"/><bgColor indexed="64"/></patternFill></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="13">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        <xf numFmtId="0" fontId="0" fillId="2" borderId="0" xfId="0" applyFill="1"/>
        <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="0" fontId="1" fillId="3" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
        <xf numFmtId="0" fontId="2" fillId="3" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
        <xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        <xf numFmtId="164" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/>
        <xf numFmtId="165" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/>
        <xf numFmtId="1" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/>
        <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="166" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/>
        </cellXfs>
        </styleSheet>
        """.stripLeading();
    }
}
