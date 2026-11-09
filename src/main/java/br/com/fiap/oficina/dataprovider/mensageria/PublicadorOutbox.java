package br.com.fiap.oficina.dataprovider.mensageria;

import br.com.fiap.oficina.dataprovider.gateway.DynamoBillingGateway;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/** Duas réplicas podem publicar a mesma mensagem; a inbox do consumidor absorve a repetição. */
@Component
@ConditionalOnProperty(name = "app.mensageria.habilitada", havingValue = "true")
@Slf4j
public class PublicadorOutbox {

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
            sqsTemplate.send(item.get("fila").s(), item.get("corpo").s());
            dynamo.updateItem(b -> b.tableName(tabela)
                    .key(Map.of(DynamoBillingGateway.PK, item.get(DynamoBillingGateway.PK),
                            DynamoBillingGateway.SK, item.get(DynamoBillingGateway.SK)))
                    .updateExpression("REMOVE #p")
                    .expressionAttributeNames(Map.of("#p", DynamoBillingGateway.OUTBOX_PENDENTE)));
            log.info("Mensagem {} ({}) publicada na fila {}", item.get(DynamoBillingGateway.PK).s(), item.get("tipo").s(), item.get("fila").s());
        }
    }
}
