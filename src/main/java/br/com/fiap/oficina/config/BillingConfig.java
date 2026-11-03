package br.com.fiap.oficina.config;

import br.com.fiap.oficina.core.gateway.BillingGateway;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway;
import br.com.fiap.oficina.core.gateway.MetricasGateway;
import br.com.fiap.oficina.core.gateway.NotificacaoAprovacaoGateway;
import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import br.com.fiap.oficina.core.gateway.TokenAprovacaoGateway;
import br.com.fiap.oficina.core.usecase.BillingUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class BillingConfig {

    @Bean
    public BillingUseCase billingUseCase(BillingGateway billing, MercadoPagoGateway mercadoPago,
                                         NotificacaoAprovacaoGateway notificacao, TokenAprovacaoGateway tokens,
                                         MetricasGateway metricas, SimuladorFalhasGateway simulador,
                                         @Value("${app.public-base-url}") String publicBaseUrl,
                                         @Value("${app.aprovacao-email.token-validade-dias}") long validadeDias,
                                         @Value("${app.cobranca.validade-qr}") Duration validadeQr) {
        return new BillingUseCase(billing, mercadoPago, notificacao, tokens, metricas, simulador,
                new BillingUseCase.Configuracao(publicBaseUrl, Duration.ofDays(validadeDias), validadeQr), Clock.systemUTC());
    }

    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${spring.cloud.aws.region.static}") String regiao,
                                         @Value("${app.dynamodb.endpoint:}") String endpoint) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(regiao));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
