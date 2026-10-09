package com.teste.vinculos.web;

import com.teste.vinculos.application.SearchRecordsUseCase;
import com.teste.vinculos.domain.AuditEntry;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.RecordFilter;
import com.teste.vinculos.domain.RecordPage;
import com.teste.vinculos.support.InMemoryQueryAuditLog;
import com.teste.vinculos.support.WebSliceConfig;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@WebMvcTest(ExportController.class)
@Import(WebSliceConfig.class)
@EnableConfigurationProperties(RateLimitProperties.class)
@TestPropertySource(properties = "app.rate-limit.enabled=false")
class ExportControllerTest {

    private static final String BODY = """
            {"year": 2026, "documentType": "CPF", "document": "05685862717", "product": "seg"}""";
    private static final List<CustomerRecord> RECORDS = List.of(
            new CustomerRecord(1, "10007037000103", "SEGURO", new BigDecimal("1234.56"), Instant.parse("2026-03-01T10:00:00Z")),
            new CustomerRecord(2, "10014956000104", "=HYPERLINK(\"x\")", new BigDecimal("0.10"), Instant.parse("2026-04-01T10:00:00Z")));

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InMemoryQueryAuditLog auditLog;

    @MockitoBean
    SearchRecordsUseCase search;

    @BeforeEach
    void setUp() {
        auditLog.entries.clear();
        given(search.export(any(RecordFilter.class))).willReturn(new RecordPage(RECORDS, null, RecordPage.Totals.EMPTY));
    }

    @Test
    void exportsCsvWithBomAndNeutralizesFormulas() {
        MvcTestResult result = export("csv");

        assertThat(result).hasStatusOk().hasContentType("text/csv;charset=UTF-8")
                .hasHeader("Content-Disposition", "attachment; filename=\"vinculos.csv\"")
                .hasHeader(ExportController.TRUNCATED_HEADER, "false");
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿id,company,product,amount,updatedAt\r\n")
                .contains("1,\"10007037000103\",\"SEGURO\",1234.56,2026-03-01T10:00:00Z")
                .contains("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(auditLog.entries).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(AuditEntry.Action.EXPORT_CSV);
            assertThat(e.resultCount()).isEqualTo(2);
        });
    }

    @Test
    void exportsRealExcelWorkbookWithNumericAmounts() throws Exception {
        MvcTestResult result = export("XLSX");

        assertThat(result).hasStatusOk()
                .hasContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("company");
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(1234.56);
            assertThat(sheet.getRow(1).getCell(4).getDateCellValue().toInstant()).isEqualTo(RECORDS.getFirst().updatedAt());
        }
        assertThat(auditLog.entries).singleElement()
                .satisfies(e -> assertThat(e.action()).isEqualTo(AuditEntry.Action.EXPORT_XLSX));
    }

    @Test
    void flagsTruncatedExport() {
        given(search.export(any(RecordFilter.class))).willReturn(new RecordPage(RECORDS, "next", RecordPage.Totals.EMPTY));

        assertThat(export("csv")).hasStatusOk().hasHeader(ExportController.TRUNCATED_HEADER, "true");
    }

    @Test
    void unknownFormatIsA400() {
        assertThat(export("pdf")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").isEqualTo("format must be csv or xlsx");
        assertThat(auditLog.entries).isEmpty();
    }

    private MvcTestResult export(String format) {
        return mvc.post().uri("/api/v1/customers/export?format=" + format)
                .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
    }
}
