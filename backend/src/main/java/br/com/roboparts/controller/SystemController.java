package br.com.roboparts.controller;

import br.com.roboparts.dto.SystemStatusResponse;
import br.com.roboparts.service.SystemStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
@Tag(name = "Fundação", description = "Verificação real da API, PostgreSQL e migrações")
public class SystemController {
    private final SystemStatusService service;

    public SystemController(SystemStatusService service) { this.service = service; }

    @GetMapping("/status")
    @Operation(summary = "Verificar a integração da fundação")
    public SystemStatusResponse status() { return service.getStatus(); }
}
