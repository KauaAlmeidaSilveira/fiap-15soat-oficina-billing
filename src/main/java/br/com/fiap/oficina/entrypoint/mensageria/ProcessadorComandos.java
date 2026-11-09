package br.com.fiap.oficina.entrypoint.mensageria;

import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.mensageria.ContextoSaga;
import br.com.fiap.oficina.core.gateway.BillingGateway;
import br.com.fiap.oficina.core.usecase.BillingUseCase;
import br.com.fiap.oficina.dataprovider.mensageria.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessadorComandos {

    private final BillingUseCase billing;
    private final BillingGateway gateway;

    public void processar(Envelope comando) {
        if (gateway.mensagemJaProcessada(comando.messageId())) {
            log.info("Comando {} ({}) da OS {} já processado", comando.messageId(), comando.tipo(), comando.osId());
            return;
        }
        ContextoSaga ctx = new ContextoSaga(comando.sagaId(), comando.osId(), comando.correlationId(), comando.messageId());
        JsonNode p = comando.payload();
        switch (comando.tipo()) {
            case "GerarOrcamento" -> billing.gerarOrcamento(ctx, orcamento(p));
            case "RegistrarDecisaoOrcamento" -> billing.registrarDecisao(ctx, p.path("aprovado").asBoolean());
            case "ExpirarOrcamento" -> billing.expirarOrcamento(ctx);
            case "CancelarOrcamento" -> billing.cancelarOrcamento(ctx, p.path("motivo").asText(), p.path("avisarCliente").asBoolean());
            case "GerarCobranca" -> billing.gerarCobranca(ctx, UUID.fromString(p.path("orcamentoId").asText()), p.path("valor").decimalValue());
            case "EncerrarCobranca" -> billing.encerrarCobranca(ctx, p.path("motivo").asText());
            case "EstornarPagamento" -> billing.estornarPagamento(ctx);
            default -> log.warn("Comando {} desconhecido na OS {}: ignorado", comando.tipo(), comando.osId());
        }
    }

    private Orcamento orcamento(JsonNode p) {
        List<Orcamento.Item> itens = new ArrayList<>();
        p.path("itens").forEach(i -> itens.add(new Orcamento.Item(i.path("nomeProduto").asText(), i.path("quantidade").asInt(),
                new BigDecimal(i.path("precoUnitario").asText()))));
        JsonNode cliente = p.path("cliente");
        JsonNode veiculo = p.path("veiculo");
        return Orcamento.builder()
                .numeroOs(p.path("numero").asText())
                .valorTotal(new BigDecimal(p.path("valorTotal").asText()))
                .clienteNome(cliente.path("nome").asText())
                .clienteEmail(cliente.path("email").asText())
                .veiculoMarca(veiculo.path("marca").asText())
                .veiculoModelo(veiculo.path("modelo").asText())
                .veiculoPlaca(veiculo.path("placa").asText())
                .itens(itens)
                .build();
    }
}
