package br.com.fiap.oficina.entrypoint.controller;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.usecase.BillingUseCase;
import br.com.fiap.oficina.dto.response.CobrancaResponse;
import br.com.fiap.oficina.dto.response.OrcamentoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Consulta de orçamento e pagamento")
public class BillingController {

    private final BillingUseCase billingUseCase;

    @GetMapping("/orcamentos/{osId}")
    @Operation(summary = "Consultar o orçamento de uma OS")
    public OrcamentoResponse orcamento(@PathVariable Long osId) {
        Orcamento o = billingUseCase.buscarOrcamento(osId);
        return new OrcamentoResponse(o.getId(), o.getOsId(), o.getStatus(), o.getValorTotal(), o.getExpiraEm(), o.getOrigemDecisao(), o.getDecididoEm());
    }

    @GetMapping("/pagamentos/{osId}")
    @Operation(summary = "Consultar o pagamento de uma OS")
    public CobrancaResponse pagamento(@PathVariable Long osId) {
        Cobranca c = billingUseCase.buscarCobranca(osId);
        return new CobrancaResponse(c.getId(), c.getOsId(), c.getStatus(), c.getValor(), c.getMpOrderId(), c.getExpiraEm(), c.getPagoEm());
    }
}
