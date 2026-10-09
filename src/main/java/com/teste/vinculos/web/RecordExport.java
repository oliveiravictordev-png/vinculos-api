package com.teste.vinculos.web;

import com.teste.vinculos.domain.CustomerRecord;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Gera o arquivo da exportação. Mesmas colunas e ordem da busca, para o arquivo bater com o que a tela mostra.
 *
 * <ul>
 *   <li><b>CSV</b> (RFC 4180, UTF-8 com BOM para o Excel reconhecer acentos): células de texto que começam com
 *       {@code = + - @} ganham um apóstrofo, para não virarem fórmula ao abrir no Excel (CSV injection).</li>
 *   <li><b>XLSX</b> via POI em modo streaming (SXSSF), que mantém só 100 linhas em memória; valor e data vão como
 *       número e data de verdade, para o usuário somar e filtrar.</li>
 * </ul>
 */
enum RecordExport {

    CSV("csv", MediaType.parseMediaType("text/csv;charset=UTF-8")) {
        @Override
        byte[] write(List<CustomerRecord> records) {
            var out = new StringBuilder("﻿").append(String.join(",", HEADERS)).append("\r\n");
            for (CustomerRecord r : records) {
                out.append(r.id()).append(',')
                        .append(text(r.company())).append(',')
                        .append(text(r.product())).append(',')
                        .append(r.amount().toPlainString()).append(',')
                        .append(r.updatedAt()).append("\r\n");
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }
    },

    XLSX("xlsx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
        @Override
        byte[] write(List<CustomerRecord> records) {
            try (var workbook = new SXSSFWorkbook(100); var out = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("records");
                CellStyle money = workbook.createCellStyle();
                money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
                CellStyle date = workbook.createCellStyle();
                date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
                Row header = sheet.createRow(0);
                for (int i = 0; i < HEADERS.size(); i++) {
                    header.createCell(i).setCellValue(HEADERS.get(i));
                }
                int line = 1;
                for (CustomerRecord r : records) {
                    Row row = sheet.createRow(line++);
                    row.createCell(0).setCellValue(r.id());
                    row.createCell(1).setCellValue(r.company());
                    row.createCell(2).setCellValue(r.product());
                    // O Excel só guarda números em ponto flutuante; o valor exato em centavos continua no CSV/JSON.
                    var amount = row.createCell(3);
                    amount.setCellValue(r.amount().doubleValue());
                    amount.setCellStyle(money);
                    var updated = row.createCell(4);
                    updated.setCellValue(Date.from(r.updatedAt()));
                    updated.setCellStyle(date);
                }
                workbook.write(out);
                workbook.dispose();
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    static final List<String> HEADERS = List.of("id", "company", "product", "amount", "updatedAt");

    private final String extension;
    private final MediaType mediaType;

    RecordExport(String extension, MediaType mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    abstract byte[] write(List<CustomerRecord> records);

    String extension() {
        return extension;
    }

    MediaType mediaType() {
        return mediaType;
    }

    private static String text(String value) {
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
