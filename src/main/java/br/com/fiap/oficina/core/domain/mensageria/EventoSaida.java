package br.com.fiap.oficina.core.domain.mensageria;

import java.util.Map;

public record EventoSaida(String tipo, ContextoSaga contexto, Map<String, Object> payload) {}
