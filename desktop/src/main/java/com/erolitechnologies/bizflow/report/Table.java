package com.erolitechnologies.bizflow.report;

import java.util.List;

/** A report as plain data: headers, string rows (already formatted) and an optional totals row. Used by screen, PDF and Excel. */
public record Table(String title, List<String> headers, List<List<String>> rows, List<String> totals, List<Boolean> numericColumns) {
    public Table {
        rows = List.copyOf(rows);
    }
}
