package br.com.fiap.oficina.dataprovider.mensageria;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code rastreio}: cabeçalhos do trace distribuído do New Relic (ver {@link RastreioDistribuido}); opcional. */
public record Envelope(UUID messageId, String tipo, int versao, UUID sagaId, Long osId,
                       String correlationId, UUID causationId, Instant ocorridoEm, JsonNode payload,
                       Map<String, String> rastreio) {

    /** Envelope sem contexto de trace, como nas mensagens anteriores a esse campo. */
    public Envelope(UUID messageId, String tipo, int versao, UUID sagaId, Long osId,
                    String correlationId, UUID causationId, Instant ocorridoEm, JsonNode payload) {
        this(messageId, tipo, versao, sagaId, osId, correlationId, causationId, ocorridoEm, payload, null);
    }
}
