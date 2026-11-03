package br.com.fiap.oficina.dto.response;

import br.com.fiap.oficina.core.domain.enums.StatusCobranca;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CobrancaResponse(UUID id, Long osId, StatusCobranca status, BigDecimal valor,
                               String mpOrderId, Instant expiraEm, Instant pagoEm) {}
