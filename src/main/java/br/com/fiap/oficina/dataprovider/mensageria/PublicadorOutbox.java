package br.com.fiap.oficina.dataprovider.mensageria;

import br.com.fiap.oficina.dataprovider.gateway.DynamoBillingGateway;
import br.com.fiap.oficina.dataprovider.observabilidade.EventosNewRelic;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Publica o outbox no SQS. Cada mensagem é reservada por escrita condicional, com prazo, antes do envio:
 * entre réplicas, uma só publica. A inbox do consumidor continua absorvendo repetições (do SQS ou de uma
 * réplica que caiu depois de enviar e antes de marcar como publicada).
 */
@Component
@ConditionalOnProperty(name = "app.mensageria.habilitada", havingValue = "true")
@Slf4j
public class PublicadorOutbox {

    static final String RESERVA = "reservadoAte";
    static final Duration PRAZO_RESERVA = Duration.ofSeconds(30);

    private final DynamoDbClient dynamo;
    private final SqsTemplate sqsTemplate;
    private final String tabela;

    public PublicadorOutbox(DynamoDbClient dynamo, SqsTemplate sqsTemplate, @Value("${app.dynamodb.tabela}") String tabela) {
        this.dynamo = dynamo;
        this.sqsTemplate = sqsTemplate;
        this.tabela = tabela;
    }

    @Scheduled(fixedDelayString = "${app.mensageria.outbox-intervalo-ms:500}")
    public void publicarPendentes() {
        var pendentes = dynamo.query(b -> b.tableName(tabela)
                .indexName(DynamoBillingGateway.INDICE_OUTBOX)
                .keyConditionExpression("#p = :um")
                .expressionAttributeNames(Map.of("#p", DynamoBillingGateway.OUTBOX_PENDENTE))
                .expressionAttributeValues(Map.of(":um", AttributeValue.fromS("1")))
                .limit(50));
        for (Map<String, AttributeValue> item : pendentes.items()) {
            Map<String, AttributeValue> chave = Map.of(DynamoBillingGateway.PK, item.get(DynamoBillingGateway.PK),
                    DynamoBillingGateway.SK, item.get(DynamoBillingGateway.SK));
            if (!reservar(chave)) {
                continue;
            }
            try {
                sqsTemplate.send(item.get("fila").s(), item.get("corpo").s());
            } catch (RuntimeException e) {
                atualizar(chave, "REMOVE " + RESERVA);
                throw e;
            }
            atualizar(chave, "REMOVE #p, " + RESERVA);
            log.info("Mensagem {} ({}) publicada na fila {}", item.get(DynamoBillingGateway.PK).s(), item.get("tipo").s(), item.get("fila").s());
            EventosNewRelic.registrar("OficinaMensagemPublicada",
                    Map.of("servico", "billing", "tipo", item.get("tipo").s(), "fila", item.get("fila").s()));
        }
    }

    /**
     * Reserva a mensagem por {@link #PRAZO_RESERVA} com escrita condicional: entre réplicas, só uma publica.
     * Se a réplica cair antes de publicar, a reserva vence e outra publica (a entrega segue pelo menos uma vez).
     */
    private boolean reservar(Map<String, AttributeValue> chave) {
        long agora = Instant.now().toEpochMilli();
        try {
            dynamo.updateItem(b -> b.tableName(tabela).key(chave)
                    .updateExpression("SET " + RESERVA + " = :ate")
                    .conditionExpression("attribute_exists(#p) AND (attribute_not_exists(" + RESERVA + ") OR "
                            + RESERVA + " < :agora)")
                    .expressionAttributeNames(Map.of("#p", DynamoBillingGateway.OUTBOX_PENDENTE))
                    .expressionAttributeValues(Map.of(
                            ":agora", AttributeValue.fromN(Long.toString(agora)),
                            ":ate", AttributeValue.fromN(Long.toString(agora + PRAZO_RESERVA.toMillis())))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private void atualizar(Map<String, AttributeValue> chave, String expressao) {
        dynamo.updateItem(b -> {
            b.tableName(tabela).key(chave).updateExpression(expressao);
            if (expressao.contains("#p")) {
                b.expressionAttributeNames(Map.of("#p", DynamoBillingGateway.OUTBOX_PENDENTE));
            }
        });
    }
}
