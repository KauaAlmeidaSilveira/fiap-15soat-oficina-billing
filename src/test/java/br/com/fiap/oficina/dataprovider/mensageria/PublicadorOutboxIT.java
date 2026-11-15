package br.com.fiap.oficina.dataprovider.mensageria;

import br.com.fiap.oficina.dataprovider.gateway.DynamoBillingGateway;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PublicadorOutboxIT {

    private static final String TABELA = "it-outbox";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamo;
    private static SqsClient sqs;
    private static SqsAsyncClient sqsAsync;
    private String fila;

    @BeforeAll
    static void criarClientes() {
        var credenciais = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
        Region regiao = Region.of(localstack.getRegion());
        dynamo = DynamoDbClient.builder().endpointOverride(localstack.getEndpoint()).region(regiao).credentialsProvider(credenciais).build();
        sqs = SqsClient.builder().endpointOverride(localstack.getEndpoint()).region(regiao).credentialsProvider(credenciais).build();
        sqsAsync = SqsAsyncClient.builder().endpointOverride(localstack.getEndpoint()).region(regiao).credentialsProvider(credenciais).build();
        dynamo.createTable(b -> b.tableName(TABELA)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo(DynamoBillingGateway.PK), atributo(DynamoBillingGateway.SK),
                        atributo(DynamoBillingGateway.OUTBOX_PENDENTE), atributo("criadoEm"))
                .keySchema(chave(DynamoBillingGateway.PK, KeyType.HASH), chave(DynamoBillingGateway.SK, KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(DynamoBillingGateway.INDICE_OUTBOX)
                        .keySchema(chave(DynamoBillingGateway.OUTBOX_PENDENTE, KeyType.HASH), chave("criadoEm", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build()));
    }

    @AfterAll
    static void fecharClientes() {
        dynamo.close();
        sqs.close();
        sqsAsync.close();
    }

    @BeforeEach
    void novaFila() {
        fila = "it-outbox-" + UUID.randomUUID();
        sqs.createQueue(b -> b.queueName(fila));
        // Esvazia o outbox de testes anteriores: cada teste conta só as próprias mensagens.
        dynamo.scan(b -> b.tableName(TABELA)).items().forEach(item -> dynamo.deleteItem(b -> b.tableName(TABELA)
                .key(Map.of(DynamoBillingGateway.PK, item.get(DynamoBillingGateway.PK),
                        DynamoBillingGateway.SK, item.get(DynamoBillingGateway.SK)))));
    }

    private PublicadorOutbox publicador() {
        return new PublicadorOutbox(dynamo, SqsTemplate.newTemplate(sqsAsync), TABELA);
    }

    private Map<String, AttributeValue> gravarPendente(String id, Long reservadoAte) {
        Map<String, AttributeValue> item = new HashMap<>(Map.of(
                DynamoBillingGateway.PK, AttributeValue.fromS("OUTBOX#" + id),
                DynamoBillingGateway.SK, AttributeValue.fromS("MSG"),
                DynamoBillingGateway.OUTBOX_PENDENTE, AttributeValue.fromS("1"),
                "criadoEm", AttributeValue.fromS(Instant.now().toString()),
                "fila", AttributeValue.fromS(fila),
                "tipo", AttributeValue.fromS("Teste"),
                "corpo", AttributeValue.fromS("{\"id\":\"" + id + "\"}")));
        if (reservadoAte != null) {
            item.put(PublicadorOutbox.RESERVA, AttributeValue.fromN(Long.toString(reservadoAte)));
        }
        dynamo.putItem(b -> b.tableName(TABELA).item(item));
        return Map.of(DynamoBillingGateway.PK, item.get(DynamoBillingGateway.PK), DynamoBillingGateway.SK, item.get(DynamoBillingGateway.SK));
    }

    private Map<String, AttributeValue> ler(Map<String, AttributeValue> chave) {
        return dynamo.getItem(b -> b.tableName(TABELA).key(chave).consistentRead(true)).item();
    }

    private List<String> recebidas() {
        String url = sqs.getQueueUrl(b -> b.queueName(fila)).queueUrl();
        List<String> corpos = new ArrayList<>();
        while (true) {
            List<Message> lote = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
            if (lote.isEmpty()) {
                return corpos;
            }
            for (Message m : lote) {
                corpos.add(m.body());
                sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(m.receiptHandle()));
            }
        }
    }

    @Test
    @DisplayName("Mensagem publicada sai de pendente e da reserva")
    void publicaELimpa() {
        var chave = gravarPendente("a", null);

        publicador().publicarPendentes();

        assertThat(recebidas()).containsExactly("{\"id\":\"a\"}");
        assertThat(ler(chave)).doesNotContainKeys(DynamoBillingGateway.OUTBOX_PENDENTE, PublicadorOutbox.RESERVA);
    }

    @Test
    @DisplayName("Mensagem reservada por outra réplica não é publicada até a reserva vencer")
    void respeitaReservaVigente() {
        var vigente = gravarPendente("vigente", Instant.now().plusSeconds(60).toEpochMilli());
        var vencida = gravarPendente("vencida", Instant.now().minusSeconds(1).toEpochMilli());

        publicador().publicarPendentes();

        assertThat(recebidas()).containsExactly("{\"id\":\"vencida\"}");
        assertThat(ler(vigente)).containsKey(DynamoBillingGateway.OUTBOX_PENDENTE);
        assertThat(ler(vencida)).doesNotContainKey(DynamoBillingGateway.OUTBOX_PENDENTE);
    }

    @Test
    @DisplayName("Duas réplicas ao mesmo tempo publicam cada mensagem uma vez só")
    void duasReplicasSemDuplicar() {
        for (int i = 0; i < 20; i++) {
            gravarPendente("m" + i, null);
        }
        PublicadorOutbox replicaA = publicador();
        PublicadorOutbox replicaB = publicador();

        CompletableFuture.allOf(
                CompletableFuture.runAsync(replicaA::publicarPendentes),
                CompletableFuture.runAsync(replicaB::publicarPendentes)).join();

        List<String> corpos = recebidas();
        assertThat(corpos).hasSize(20).doesNotHaveDuplicates();
    }

    private static AttributeDefinition atributo(String nome) {
        return AttributeDefinition.builder().attributeName(nome).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement chave(String nome, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(nome).keyType(tipo).build();
    }
}
