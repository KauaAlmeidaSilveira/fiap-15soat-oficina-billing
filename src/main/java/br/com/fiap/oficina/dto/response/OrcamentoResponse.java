package br.com.fiap.oficina.dto.response;

import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrcamentoResponse(UUID id, Long osId, StatusOrcamento status, BigDecimal valorTotal,
                                Instant expiraEm, String origemDecisao, Instant decididoEm) {}
