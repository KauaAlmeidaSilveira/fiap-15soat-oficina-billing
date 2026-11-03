package br.com.fiap.oficina.core.domain.entity;

import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Cobranca {

    private UUID id;
    private Long osId;
    private UUID sagaId;
    private String correlationId;
    private UUID orcamentoId;
    private StatusCobranca status;
    private BigDecimal valor;
    private String mpOrderId;
    private String paymentId;
    private String qrData;
    private Instant expiraEm;
    private Instant pagoEm;
}
