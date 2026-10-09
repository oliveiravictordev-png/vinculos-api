package com.teste.vinculos.web;

import com.teste.vinculos.application.AuditQueriesUseCase;
import com.teste.vinculos.application.SearchRecordsUseCase;
import com.teste.vinculos.domain.AuditEntry.Action;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.InvalidDataException;
import com.teste.vinculos.domain.RecordPage;
import com.teste.vinculos.web.dto.SearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Locale;

/**
 * Exportação da busca em CSV ou Excel. Protegida em camadas: exige o escopo {@code customers:export}, tem rate
 * limit próprio e mais apertado ({@link RateLimitFilter}), é limitada a
 * {@value SearchRecordsUseCase#MAX_EXPORT_ROWS} linhas e cada download fica na auditoria.
 */
@RestController
@Profile("!seed")
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
@SecurityRequirement(name = "bearerAuth")
public class ExportController {

    static final String TRUNCATED_HEADER = "X-Export-Truncated";

    private final SearchRecordsUseCase search;
    private final AuditQueriesUseCase audit;

    public ExportController(SearchRecordsUseCase search, AuditQueriesUseCase audit) {
        this.search = search;
        this.audit = audit;
    }

    @PostMapping("/export")
    @Operation(summary = "Exporta a busca em CSV ou Excel",
            description = "Mesmo corpo da busca (limit e cursor são ignorados). " + ApiDocs.EXPORT_LIMIT + ".")
    @ApiResponse(responseCode = "200", description = "Arquivo para download",
            content = {@Content(mediaType = "text/csv"),
                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")})
    @ApiResponse(responseCode = "403", description = "Token sem o escopo customers:export",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<byte[]> export(@RequestBody SearchRequest request,
                                         @Parameter(description = "csv ou xlsx") @RequestParam(defaultValue = "csv") String format,
                                         Principal principal, HttpServletRequest http) {
        RecordExport type = parse(format);
        CustomerKey key = request.key();
        Action action = type == RecordExport.CSV ? Action.EXPORT_CSV : Action.EXPORT_XLSX;
        RecordPage page = audit.run(Callers.of(principal, http), action, key,
                () -> search.export(request.filter()),
                result -> result.records().size());
        return ResponseEntity.ok()
                .contentType(type.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("vinculos." + type.extension()).build().toString())
                .header(TRUNCATED_HEADER, Boolean.toString(page.nextCursor() != null))
                .body(type.write(page.records()));
    }

    private static RecordExport parse(String format) {
        try {
            return RecordExport.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDataException("format must be csv or xlsx");
        }
    }
}
