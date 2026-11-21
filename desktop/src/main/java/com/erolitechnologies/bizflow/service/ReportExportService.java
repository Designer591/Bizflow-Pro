package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.report.ExcelExporter;
import com.erolitechnologies.bizflow.report.PdfWriter;
import com.erolitechnologies.bizflow.report.Table;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ReportExportService {
    private final BusinessService business;
    private final AuditService audit;

    public ReportExportService(BusinessService business, AuditService audit) { this.business = business; this.audit = audit; }

    private PdfWriter.Header header(Session s) {
        var b = business.get();
        String line = String.join(" | ", java.util.stream.Stream.of(b.address(), b.phone(), b.email()).filter(x -> x != null && !x.isBlank()).toList());
        return new PdfWriter.Header(b.name(), line, s.fullName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    public void exportPdf(Session s, Table t, Path file) {
        s.require(Permission.REPORT_EXPORT);
        try {
            PdfWriter.write(file, header(s), t);
            audit.log(s, "REPORT_EXPORTED", "report", null, "PDF: " + t.title());
        } catch (IOException e) {
            throw new AppException("Could not save the PDF. Check that the folder exists and the file is not open elsewhere.", e);
        }
    }

    public void exportExcel(Session s, Table t, Path file) {
        s.require(Permission.REPORT_EXPORT);
        try {
            ExcelExporter.write(file, header(s), t);
            audit.log(s, "REPORT_EXPORTED", "report", null, "Excel: " + t.title());
        } catch (IOException e) {
            throw new AppException("Could not save the Excel file. Check that the folder exists and the file is not open elsewhere.", e);
        }
    }
}
