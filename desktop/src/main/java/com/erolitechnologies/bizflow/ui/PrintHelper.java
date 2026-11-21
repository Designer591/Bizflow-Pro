package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.EscPosPrinter;
import javafx.stage.Window;

import java.util.List;

/** Prints a receipt: direct ESC/POS to the chosen thermal printer if enabled, otherwise the normal system print dialog. */
public final class PrintHelper {
    private PrintHelper() {}

    public static void printReceipt(AppContext ctx, List<String> lines, Window owner) {
        if (ctx.settings.getBool("receipt_escpos")) {
            EscPosPrinter.send(ctx.settings.get("receipt_printer"), EscPosPrinter.build(lines, ctx.settings.getBool("receipt_open_drawer")));
        } else {
            Ui.printText(lines, owner);
        }
    }
}
