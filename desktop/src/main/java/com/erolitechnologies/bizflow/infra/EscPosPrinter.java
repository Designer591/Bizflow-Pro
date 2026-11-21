package com.erolitechnologies.bizflow.infra;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct thermal-receipt printing with standard ESC/POS commands (Epson-compatible, which covers most 58mm/80mm printers).
 * Text is sent as plain ASCII so it prints correctly on any code page; accented letters lose their accents and
 * other symbols become '?'. Pure JDK (javax.print), no extra libraries.
 */
public final class EscPosPrinter {
    private static final byte ESC = 0x1B, GS = 0x1D;

    private EscPosPrinter() {}

    /** Builds the raw printer bytes: initialise, text, feed, optional drawer pulse, partial cut. */
    public static byte[] build(List<String> lines, boolean openDrawer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ESC); out.write('@');                       // initialise printer
        for (String line : lines) {
            byte[] b = ascii(line).getBytes(StandardCharsets.US_ASCII);
            out.write(b, 0, b.length);
            out.write(0x0A);
        }
        out.write(ESC); out.write('d'); out.write(4);          // feed 4 lines
        if (openDrawer) { out.write(ESC); out.write('p'); out.write(0); out.write(60); out.write(120); }  // drawer kick, pin 2
        out.write(GS); out.write('V'); out.write(66); out.write(0); // partial cut
        return out.toByteArray();
    }

    static String ascii(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(n.length());
        for (char c : n.toCharArray()) sb.append(c >= 32 && c < 127 ? c : (c == '\t' ? ' ' : '?'));
        return sb.toString();
    }

    public static List<String> printerNames() {
        List<String> names = new ArrayList<>();
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) names.add(ps.getName());
        return names;
    }

    /** Sends raw bytes to the named Windows printer. Throws AppException with a plain message on failure. */
    public static void send(String printerName, byte[] data) {
        if (printerName == null || printerName.isBlank()) throw new AppException("Choose a receipt printer in Settings > Receipts.");
        PrintService target = null;
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) if (ps.getName().equals(printerName)) target = ps;
        if (target == null) throw new AppException("The printer \"" + printerName + "\" was not found. Check that it is switched on and installed.");
        try {
            DocPrintJob job = target.createPrintJob();
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null);
        } catch (javax.print.PrintException e) {
            throw new AppException("The receipt could not be sent to the printer. Check the cable, paper and power.", e);
        }
    }
}
