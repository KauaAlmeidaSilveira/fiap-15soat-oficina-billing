package br.com.fiap.oficina.entrypoint.mensageria;

import br.com.fiap.oficina.core.gateway.TokenAprovacaoGateway;
import br.com.fiap.oficina.dataprovider.gateway.DynamoBillingGateway;
import br.com.fiap.oficina.dataprovider.mensageria.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BillingFluxoIT {

    private static final String COMANDOS = "it-billing-comandos";
    private static final String EVENTOS = "it-saga-eventos";
    private static final String TABELA = "it-billing";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.DYNAMODB);

    private static final WireMockServer mp = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        mp.start();
        var credenciais = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
        try (SqsClient sqs = SqsClient.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(credenciais).build()) {
            sqs.createQueue(b -> b.queueName(COMANDOS));
            sqs.createQueue(b -> b.queueName(EVENTOS));
        }
        try (DynamoDbClient dynamo = DynamoDbClient.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(credenciais).build()) {
            dynamo.createTable(b -> b.tableName(TABELA)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            atributo(DynamoBillingGateway.PK), atributo(DynamoBillingGateway.SK),
                            atributo(DynamoBillingGateway.OUTBOX_PENDENTE), atributo("criadoEm"))
                    .keySchema(chave(DynamoBillingGateway.PK, KeyType.HASH), chave(DynamoBillingGateway.SK, KeyType.RANGE))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName(DynamoBillingGateway.INDICE_OUTBOX)
                            .keySchema(chave(DynamoBillingGateway.OUTBOX_PENDENTE, KeyType.HASH), chave("criadoEm", KeyType.RANGE))
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build()));
        }
        System.setProperty("aws.accessKeyId", localstack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localstack.getSecretKey());
        registry.add("app.dynamodb.tabela", () -> TABELA);
        registry.add("app.dynamodb.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("app.mercadopago.base-url", mp::baseUrl);
        registry.add("app.mensageria.habilitada", () -> "true");
        registry.add("app.mensageria.fila-comandos", () -> COMANDOS);
        registry.add("app.mensageria.fila-eventos", () -> EVENTOS);
        registry.add("spring.cloud.aws.sqs.enabled", () -> "true");
        registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
    }

    private static AttributeDefinition atributo(String nome) {
        return AttributeDefinition.builder().attributeName(nome).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement chave(String nome, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(nome).keyType(tipo).build();
    }

    @AfterAll
    static void pararMercadoPago() {
        mp.stop();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TokenAprovacaoGateway tokens;

    private final UUID saga = UUID.randomUUID();

    private SqsClient sqs() {
        return SqsClient.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()))).build();
    }

    private void enviar(SqsClient sqs, String tipo, Object payload) throws Exception {
        Envelope comando = new Envelope(UUID.randomUUID(), tipo, 1, saga, 7L, "corr-it", null, Instant.now(), objectMapper.valueToTree(payload));
        String url = sqs.getQueueUrl(b -> b.queueName(COMANDOS)).queueUrl();
        String corpo = objectMapper.writeValueAsString(comando);
        sqs.sendMessage(b -> b.queueUrl(url).messageBody(corpo));
    }

    private Envelope esperar(SqsClient sqs, String tipo) throws Exception {
        String url = sqs.getQueueUrl(b -> b.queueName(EVENTOS)).queueUrl();
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            for (Message m : sqs.receiveMessage(b -> b.queueUrl(url).waitTimeSeconds(2).maxNumberOfMessages(5)).messages()) {
                sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(m.receiptHandle()));
                Envelope evento = objectMapper.readValue(m.body(), Envelope.class);
                if (tipo.equals(evento.tipo())) {
                    return evento;
                }
            }
        }
        throw new AssertionError("Evento " + tipo + " não chegou");
    }

    @Test
    @DisplayName("Orçamento, link, cobrança, webhook e estorno contra DynamoDB, SQS e Mercado Pago simulados")
    void fluxoCompleto() throws Exception {
        mp.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id":"ORD1","status":"created","external_reference":"7","type_response":{"qr_data":"qr"}}""")));
        mp.stubFor(get("/v1/orders/ORD1").willReturn(okJson("""
                {"id":"ORD1","status":"processed","external_reference":"7",
                 "transactions":{"payments":[{"id":"PAY1","status":"processed"}]}}""")));
        mp.stubFor(post("/v1/orders/ORD1/refund").willReturn(okJson("{}")));

        try (SqsClient sqs = sqs()) {
            enviar(sqs, "GerarOrcamento", Map.of(
                    "numero", "OS1", "valorTotal", "150.00",
                    "cliente", Map.of("nome", "Maria", "email", "maria@email.com"),
                    "veiculo", Map.of("marca", "Toyota", "modelo", "Corolla", "placa", "ABC1234"),
                    "itens", List.of(Map.of("nomeProduto", "Filtro", "quantidade", 1, "precoUnitario", "150.00"))));
            Envelope gerado = esperar(sqs, "OrcamentoGerado");
            assertThat(gerado.sagaId()).isEqualTo(saga);
            assertThat(gerado.correlationId()).isEqualTo("corr-it");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/aprovacao-os")
                    .param("token", tokens.gerarToken(7L, true))).andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/aprovacao-os")
                    .param("token", tokens.gerarToken(7L, true))).andExpect(status().isConflict());
            esperar(sqs, "OrcamentoAprovado");

            enviar(sqs, "GerarCobranca", Map.of("orcamentoId", gerado.payload().path("orcamentoId").asText(), "valor", 150.00));
            esperar(sqs, "CobrancaGerada");

            for (int i = 0; i < 2; i++) {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/mercado-pago")
                        .param("type", "order").param("data.id", "ORD1")).andExpect(status().isOk());
            }
            Envelope pago = esperar(sqs, "PagamentoConfirmado");
            assertThat(pago.payload().path("paymentId").asText()).isEqualTo("PAY1");

            enviar(sqs, "EstornarPagamento", Map.of("motivo", "Falha ao enviar para execução"));
            esperar(sqs, "PagamentoEstornado");
        }
    }
}
