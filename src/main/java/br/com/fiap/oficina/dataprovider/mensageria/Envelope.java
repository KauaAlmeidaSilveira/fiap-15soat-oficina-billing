package br.com.fiap.oficina.dataprovider.mensageria;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Envelope(UUID messageId, String tipo, int versao, UUID sagaId, Long osId,
                       String correlationId, UUID causationId, Instant ocorridoEm, JsonNode payload) {}
