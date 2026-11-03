package br.com.fiap.oficina.core.domain.entity;

import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Orcamento {

    private UUID id;
    private Long osId;
    private UUID sagaId;
    private String correlationId;
    private String numeroOs;
    private StatusOrcamento status;
    private BigDecimal valorTotal;
    private Instant expiraEm;
    private String clienteNome;
    private String clienteEmail;
    private String veiculoMarca;
    private String veiculoModelo;
    private String veiculoPlaca;
    private List<Item> itens;
    private String origemDecisao;
    private Instant decididoEm;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String nomeProduto;
        private Integer quantidade;
        private BigDecimal precoUnitario;
    }
}
