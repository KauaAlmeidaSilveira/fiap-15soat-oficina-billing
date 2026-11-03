package br.com.fiap.oficina.entrypoint.controller;

import br.com.fiap.oficina.dataprovider.simulacao.SimuladorFalhasPorVariavel;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulacao/falha")
@Profile("simulacao")
@RequiredArgsConstructor
@Hidden
public class SimulacaoController {

    private final SimuladorFalhasPorVariavel simulador;

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> ligar(@RequestParam String ponto) {
        simulador.simular(ponto);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> desligar() {
        simulador.simular("");
        return ResponseEntity.noContent().build();
    }
}
