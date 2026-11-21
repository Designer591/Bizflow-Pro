package com.erolitechnologies.bizflow.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Excel export with business header, styled header row, real numeric cells (so users can sum/sort) and a totals row. */
public final class ExcelExporter {
    private ExcelExporter() {}

    public static void write(Path file, PdfWriter.Header head, Table t) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet(sheetName(t.title()));
            Font bold = wb.createFont(); bold.setBold(true);
            Font title = wb.createFont(); title.setBold(true); title.setFontHeightInPoints((short) 14);
            Font white = wb.createFont(); white.setBold(true); white.setColor(IndexedColors.WHITE.getIndex());
            CellStyle hs = wb.createCellStyle(); hs.setFont(white); hs.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle ts = wb.createCellStyle(); ts.setFont(bold); ts.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); ts.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle num = wb.createCellStyle(); num.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle numBold = wb.createCellStyle(); numBold.cloneStyleFrom(num); numBold.setFont(bold); numBold.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); numBold.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle titleStyle = wb.createCellStyle(); titleStyle.setFont(title);

            int r = 0;
            Row r0 = sh.createRow(r++); r0.createCell(0).setCellValue(head.businessName()); r0.getCell(0).setCellStyle(titleStyle);
            sh.createRow(r++).createCell(0).setCellValue(head.businessLine());
            Row r2 = sh.createRow(r++); r2.createCell(0).setCellValue(t.title()); r2.getCell(0).setCellStyle(titleStyle);
            sh.createRow(r++).createCell(0).setCellValue("Generated " + head.generatedAt() + " by " + head.generatedBy());
            r++;
            Row hr = sh.createRow(r++);
            for (int i = 0; i < t.headers().size(); i++) { Cell c = hr.createCell(i); c.setCellValue(t.headers().get(i)); c.setCellStyle(hs); }
            for (List<String> row : t.rows()) fill(sh.createRow(r++), row, t, num, null);
            if (t.totals() != null) fill(sh.createRow(r++), t.totals(), t, numBold, ts);
            for (int i = 0; i < t.headers().size(); i++) sh.autoSizeColumn(i);
            sh.createFreezePane(0, 6);
            wb.write(out);
        }
    }

    private static void fill(Row row, List<String> values, Table t, CellStyle numStyle, CellStyle textStyle) {
        for (int i = 0; i < values.size(); i++) {
            Cell c = row.createCell(i);
            String v = values.get(i);
            boolean numeric = t.numericColumns() != null && i < t.numericColumns().size() && t.numericColumns().get(i);
            if (numeric && !v.isBlank()) {
                try { c.setCellValue(new BigDecimal(v).doubleValue()); c.setCellStyle(numStyle); continue; } catch (NumberFormatException ignored) { }
            }
            c.setCellValue(v);
            if (textStyle != null) c.setCellStyle(textStyle);
        }
    }

    private static String sheetName(String title) {
        String n = title.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }
}
