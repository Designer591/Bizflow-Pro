package com.erolitechnologies.bizflow.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal dependency-free PDF writer for tabular reports (A4, landscape when wide, Helvetica, repeating header
 * row, page numbers). Text is WinAnsi encoded; characters outside Latin-1 are replaced with '?'.
 */
public final class PdfWriter {
    private static final double[] CHAR_W_UNUSED = null;

    public record Header(String businessName, String businessLine, String generatedBy, String generatedAt) {}

    private PdfWriter() {}

    public static void write(Path file, Header head, Table table) throws IOException {
        Files.write(file, render(head, table));
    }

    public static byte[] render(Header head, Table t) {
        int cols = t.headers().size();
        boolean landscape = cols > 6;
        double pw = landscape ? 842 : 595, ph = landscape ? 595 : 842, margin = 36;
        double usable = pw - 2 * margin;
        double[] widths = columnWidths(t, usable);
        double rowH = 16, fs = 8.5;

        List<String> pages = new ArrayList<>();
        StringBuilder cur = null;
        double y = 0;
        int pageNo = 0;
        List<List<String>> all = new ArrayList<>(t.rows());
        if (t.totals() != null) all.add(t.totals());
        int idx = 0;
        do {
            cur = new StringBuilder();
            pageNo++;
            y = ph - margin;
            text(cur, head.businessName(), margin, y, 15, true);
            y -= 16;
            text(cur, head.businessLine(), margin, y, 8.5, false);
            y -= 22;
            text(cur, t.title(), margin, y, 12, true);
            y -= 14;
            text(cur, "Generated " + head.generatedAt() + " by " + head.generatedBy(), margin, y, 8, false);
            y -= 18;
            y = headerRow(cur, t, widths, margin, y, rowH, fs);
            while (idx < all.size() && y - rowH > margin + 20) {
                boolean isTotal = t.totals() != null && idx == all.size() - 1;
                y = dataRow(cur, t, all.get(idx), widths, margin, y, rowH, fs, isTotal, idx);
                idx++;
            }
            text(cur, "BizFlow Pro - Powered by EROLITECHNOLOGIES", margin, margin - 6, 7, false);
            pages.add(cur.toString());
        } while (idx < all.size());

        int total = pages.size();
        for (int i = 0; i < total; i++)
            pages.set(i, pages.get(i) + textStr("Page " + (i + 1) + " of " + total, pw - margin - 60, margin - 6, 7, false));
        return assemble(pages, pw, ph);
    }

    private static double[] columnWidths(Table t, double usable) {
        int n = t.headers().size();
        double[] want = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int max = t.headers().get(i).length();
            for (List<String> r : t.rows()) if (i < r.size()) max = Math.max(max, Math.min(r.get(i).length(), 40));
            want[i] = Math.max(4, max) * 4.6 + 8;
            sum += want[i];
        }
        double scale = usable / sum;
        for (int i = 0; i < n; i++) want[i] *= scale;
        return want;
    }

    private static double headerRow(StringBuilder s, Table t, double[] w, double x0, double y, double rowH, double fs) {
        s.append("0.08 0.18 0.31 rg ").append(x0).append(' ').append(y - rowH + 4).append(' ').append(sum(w)).append(' ').append(rowH).append(" re f\n");
        double x = x0;
        for (int i = 0; i < w.length; i++) {
            boolean right = t.numericColumns() != null && t.numericColumns().get(i);
            cell(s, t.headers().get(i), x, y - 8, w[i], fs, true, right, "1 1 1 rg");
            x += w[i];
        }
        return y - rowH;
    }

    private static double dataRow(StringBuilder s, Table t, List<String> row, double[] w, double x0, double y, double rowH, double fs, boolean total, int idx) {
        if (total) s.append("0.85 0.92 0.99 rg ").append(x0).append(' ').append(y - rowH + 4).append(' ').append(sum(w)).append(' ').append(rowH).append(" re f\n");
        else if (idx % 2 == 1) s.append("0.96 0.97 0.98 rg ").append(x0).append(' ').append(y - rowH + 4).append(' ').append(sum(w)).append(' ').append(rowH).append(" re f\n");
        double x = x0;
        for (int i = 0; i < w.length; i++) {
            boolean right = t.numericColumns() != null && t.numericColumns().get(i);
            cell(s, i < row.size() ? row.get(i) : "", x, y - 8, w[i], fs, total, right, "0.1 0.1 0.1 rg");
            x += w[i];
        }
        return y - rowH;
    }

    private static void cell(StringBuilder s, String text, double x, double y, double w, double fs, boolean bold, boolean right, String color) {
        int maxChars = (int) ((w - 8) / (fs * 0.52));
        String t = text.length() > maxChars ? text.substring(0, Math.max(0, maxChars - 1)) + "." : text;
        double tw = t.length() * fs * 0.52;
        double tx = right ? x + w - 4 - tw : x + 4;
        s.append(color).append(' ').append(textStr(t, tx, y, fs, bold));
    }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    private static void text(StringBuilder s, String t, double x, double y, double size, boolean bold) {
        s.append("0.08 0.18 0.31 rg ").append(textStr(t, x, y, size, bold));
    }

    private static String textStr(String t, double x, double y, double size, boolean bold) {
        return "BT /" + (bold ? "F2" : "F1") + " " + size + " Tf " + x + " " + y + " Td (" + esc(t) + ") Tj ET\n";
    }

    private static String esc(String t) {
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else if (c > 255) b.append('?');
            else b.append(c);
        }
        return b.toString();
    }

    private static byte[] assemble(List<String> pages, double pw, double ph) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            write(out, "%PDF-1.4\n");
            int n = pages.size();
            // objects: 1 catalog, 2 pages, 3 F1, 4 F2, then per page: page obj, content obj
            obj(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(5 + i * 2).append(" 0 R ");
            obj(out, offsets, "<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>");
            obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
            obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
            for (int i = 0; i < n; i++) {
                int contentId = 6 + i * 2;
                obj(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + pw + " " + ph + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentId + " 0 R >>");
                byte[] body = pages.get(i).getBytes(StandardCharsets.ISO_8859_1);
                offsets.add(out.size());
                write(out, (offsets.size()) + " 0 obj\n<< /Length " + body.length + " >>\nstream\n");
                out.write(body);
                write(out, "\nendstream\nendobj\n");
            }
            int xref = out.size();
            write(out, "xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
            for (int off : offsets) write(out, String.format("%010d 00000 n \n", off));
            write(out, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void obj(ByteArrayOutputStream out, List<Integer> offsets, String body) throws IOException {
        offsets.add(out.size());
        write(out, offsets.size() + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); }
}
