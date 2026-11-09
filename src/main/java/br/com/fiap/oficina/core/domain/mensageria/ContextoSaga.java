package br.com.fiap.oficina.core.domain.mensageria;

import java.util.UUID;

public record ContextoSaga(UUID sagaId, Long osId, String correlationId, UUID causationId) {}
