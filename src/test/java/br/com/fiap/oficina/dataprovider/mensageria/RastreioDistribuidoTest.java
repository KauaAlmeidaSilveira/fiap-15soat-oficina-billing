package br.com.fiap.oficina.dataprovider.mensageria;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RastreioDistribuidoTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("Sem o agente do New Relic não há trace para capturar")
    void semAgenteNaoCaptura() {
        assertThat(RastreioDistribuido.capturar()).isNull();
    }

    @Test
    @DisplayName("Continuar o trace sem o agente, com ou sem cabeçalhos, não lança erro")
    void continuarSemAgente() {
        assertThatCode(() -> RastreioDistribuido.continuar("fila", "Tipo", Map.of("traceparent", "00-abc-def-01")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RastreioDistribuido.continuar("fila", "Tipo", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("O envelope leva os cabeçalhos de trace e continua lendo mensagens sem eles")
    void envelopeComESemRastreio() throws Exception {
        Envelope comRastreio = new Envelope(UUID.randomUUID(), "Tipo", 1, UUID.randomUUID(), 1L, "corr", null,
                Instant.parse("2026-11-15T00:00:00Z"), mapper.createObjectNode(), Map.of("traceparent", "00-abc-def-01"));

        Envelope lido = mapper.readValue(mapper.writeValueAsString(comRastreio), Envelope.class);
        assertThat(lido.rastreio()).containsEntry("traceparent", "00-abc-def-01");

        String antigo = """
                {"messageId":"%s","tipo":"Tipo","versao":1,"sagaId":"%s","osId":1,"correlationId":"corr",
                 "causationId":null,"ocorridoEm":"2026-11-15T00:00:00Z","payload":{}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        assertThat(mapper.readValue(antigo, Envelope.class).rastreio()).isNull();
    }
}
