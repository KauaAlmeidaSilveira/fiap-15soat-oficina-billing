package br.com.fiap.oficina.dataprovider.gateway;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.mensageria.EventoSaida;
import br.com.fiap.oficina.core.gateway.BillingGateway;
import br.com.fiap.oficina.dataprovider.mensageria.Envelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tabela única: orçamento e cobrança sob {@code OS#id}, mais outbox e inbox. O documento vai em JSON
 * no atributo {@code dados}; {@code status} fica à parte para as escritas condicionais.
 */
@Component
@Slf4j
public class DynamoBillingGateway implements BillingGateway {

    public static final String PK = "pk";
    public static final String SK = "sk";
    public static final String INDICE_OUTBOX = "outbox-pendente";
    public static final String OUTBOX_PENDENTE = "outboxPendente";
    private static final String SK_ORCAMENTO = "ORCAMENTO";
    private static final String SK_COBRANCA = "COBRANCA";
    private static final long RETENCAO_INBOX_SEGUNDOS = 14 * 24 * 3600L;

    private final DynamoDbClient dynamo;
    private final ObjectMapper objectMapper;
    private final String tabela;
    private final String filaEventos;

    public DynamoBillingGateway(DynamoDbClient dynamo, ObjectMapper objectMapper,
                                @Value("${app.dynamodb.tabela}") String tabela,
                                @Value("${app.mensageria.fila-eventos}") String filaEventos) {
        this.dynamo = dynamo;
        this.objectMapper = objectMapper;
        this.tabela = tabela;
        this.filaEventos = filaEventos;
    }

    @Override
    public boolean gravar(Alteracao alteracao) {
        List<TransactWriteItem> escritas = new ArrayList<>();
        if (alteracao.orcamento() != null) {
            Orcamento o = alteracao.orcamento();
            escritas.add(escritaDeEstado(o.getOsId(), SK_ORCAMENTO, o.getStatus().name(), o,
                    alteracao.orcamentoEsperado() == null ? null : alteracao.orcamentoEsperado().name()));
        }
        if (alteracao.cobranca() != null) {
            Cobranca c = alteracao.cobranca();
            escritas.add(escritaDeEstado(c.getOsId(), SK_COBRANCA, c.getStatus().name(), c,
                    alteracao.cobrancaEsperada() == null ? null : alteracao.cobrancaEsperada().name()));
        }
        alteracao.eventos().forEach(evento -> escritas.add(escritaDeOutbox(evento)));
        if (alteracao.mensagemId() != null) {
            escritas.add(escritaDeInbox(alteracao.mensagemId()));
        }
        try {
            dynamo.transactWriteItems(b -> b.transactItems(escritas));
            return true;
        } catch (TransactionCanceledException e) {
            log.info("Gravação condicional recusada: {}", e.cancellationReasons());
            return false;
        }
    }

    @Override
    public Optional<Orcamento> buscarOrcamento(Long osId) {
        return ler(osId, SK_ORCAMENTO, Orcamento.class);
    }

    @Override
    public Optional<Cobranca> buscarCobranca(Long osId) {
        return ler(osId, SK_COBRANCA, Cobranca.class);
    }

    @Override
    public boolean mensagemJaProcessada(UUID mensagemId) {
        return dynamo.getItem(b -> b.tableName(tabela).key(chave("INBOX#" + mensagemId, "MSG"))).hasItem();
    }

    private <T> Optional<T> ler(Long osId, String sk, Class<T> tipo) {
        var resposta = dynamo.getItem(b -> b.tableName(tabela).key(chave("OS#" + osId, sk)).consistentRead(true));
        if (!resposta.hasItem()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(resposta.item().get("dados").s(), tipo));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Documento inválido em OS#" + osId + "/" + sk, e);
        }
    }

    private TransactWriteItem escritaDeEstado(Long osId, String sk, String status, Object documento, String esperado) {
        Map<String, AttributeValue> item = new HashMap<>(chave("OS#" + osId, sk));
        item.put("status", s(status));
        item.put("dados", s(json(documento)));
        Put.Builder put = Put.builder().tableName(tabela).item(item);
        if (esperado == null) {
            put.conditionExpression("attribute_not_exists(pk)");
        } else {
            put.conditionExpression("#s = :esperado")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(":esperado", s(esperado)));
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    private TransactWriteItem escritaDeOutbox(EventoSaida evento) {
        UUID id = UUID.randomUUID();
        Instant agora = Instant.now();
        var ctx = evento.contexto();
        Envelope envelope = new Envelope(id, evento.tipo(), 1, ctx.sagaId(), ctx.osId(), ctx.correlationId(),
                ctx.causationId(), agora, objectMapper.valueToTree(evento.payload()));
        Map<String, AttributeValue> item = new HashMap<>(chave("OUTBOX#" + id, "MSG"));
        item.put(OUTBOX_PENDENTE, s("1"));
        item.put("criadoEm", s(agora.toString()));
        item.put("fila", s(filaEventos));
        item.put("tipo", s(evento.tipo()));
        item.put("corpo", s(json(envelope)));
        return TransactWriteItem.builder().put(p -> p.tableName(tabela).item(item)).build();
    }

    private TransactWriteItem escritaDeInbox(UUID mensagemId) {
        Map<String, AttributeValue> item = new HashMap<>(chave("INBOX#" + mensagemId, "MSG"));
        item.put("expiraEm", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond() + RETENCAO_INBOX_SEGUNDOS)));
        return TransactWriteItem.builder()
                .put(p -> p.tableName(tabela).item(item).conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    private Map<String, AttributeValue> chave(String pk, String sk) {
        return Map.of(PK, s(pk), SK, s(sk));
    }

    private AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }

    private String json(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Documento não serializável", e);
        }
    }
}
