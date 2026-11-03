package br.com.fiap.oficina.dataprovider.gateway;

import br.com.fiap.oficina.core.gateway.MercadoPagoGateway;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * API de orders do QR dinâmico. Ainda sem teste com conta real: o caminho do {@code qr_data}, o
 * método do cancelamento e o status de pagamento recusado seguem a documentação e precisam ser
 * conferidos na compra de teste.
 */
@Component
@Slf4j
public class MercadoPagoRestGateway implements MercadoPagoGateway {

    private static final int TENTATIVAS = 3;

    private final RestClient rest;
    private final String externalPosId;

    public MercadoPagoRestGateway(@Value("${app.mercadopago.base-url}") String baseUrl,
                                  @Value("${app.mercadopago.access-token:}") String accessToken,
                                  @Value("${app.mercadopago.external-pos-id:}") String externalPosId) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(2));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(fabrica)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .build();
        this.externalPosId = externalPosId;
    }

    @Override
    public Order criarOrder(String chaveIdempotencia, String externalReference, BigDecimal valor, Duration validade) {
        String total = valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        Map<String, Object> corpo = Map.of(
                "type", "qr",
                "external_reference", externalReference,
                "total_amount", total,
                "expiration_time", validade.toString(),
                "config", Map.of("qr", Map.of("external_pos_id", externalPosId, "mode", "dynamic")),
                "transactions", Map.of("payments", List.of(Map.of("amount", total))));
        return comTentativas("criar order da referência " + externalReference, () -> paraOrder(rest.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", chaveIdempotencia)
                .body(corpo)
                .retrieve()
                .body(JsonNode.class)));
    }

    @Override
    public Order consultarOrder(String orderId) {
        return comTentativas("consultar order " + orderId,
                () -> paraOrder(rest.get().uri("/v1/orders/{id}", orderId).retrieve().body(JsonNode.class)));
    }

    @Override
    public void cancelarOrder(String orderId) {
        try {
            rest.post().uri("/v1/orders/{id}/cancel", orderId)
                    .header("X-Idempotency-Key", "cancelar-" + orderId)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new CancelamentoRecusadoException("Order " + orderId + " não está mais em created", e);
        } catch (RestClientException e) {
            throw new FalhaMercadoPagoException("Falha ao cancelar a order " + orderId, e);
        }
    }

    @Override
    public void reembolsarOrder(String orderId, String chaveIdempotencia) {
        comTentativas("reembolsar order " + orderId, () -> rest.post()
                .uri("/v1/orders/{id}/refund", orderId)
                .header("X-Idempotency-Key", chaveIdempotencia)
                .retrieve().toBodilessEntity());
    }

    private <T> T comTentativas(String operacao, Supplier<T> chamada) {
        RestClientException ultima = null;
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            try {
                return chamada.get();
            } catch (HttpClientErrorException e) {
                throw new FalhaMercadoPagoException("Mercado Pago recusou " + operacao + ": " + e.getStatusCode(), e);
            } catch (RestClientException e) {
                ultima = e;
                log.warn("Tentativa {} de {} falhou: {}", tentativa, operacao, e.getMessage());
            }
        }
        throw new FalhaMercadoPagoException("Falha ao " + operacao, ultima);
    }

    private Order paraOrder(JsonNode json) {
        JsonNode pagamentos = json.path("transactions").path("payments");
        JsonNode pagamento = pagamentos.isArray() && !pagamentos.isEmpty() ? pagamentos.get(0) : json.path("_");
        String statusPagamento = pagamento.path("status").asText("");
        String qrData = json.path("type_response").path("qr_data").asText(json.path("qr_data").asText(null));
        return new Order(json.path("id").asText(), status(json.path("status").asText()), json.path("external_reference").asText(null),
                qrData, pagamento.path("id").asText(null),
                "failed".equals(statusPagamento) || "rejected".equals(statusPagamento));
    }

    private StatusOrder status(String status) {
        return switch (status) {
            case "processed" -> StatusOrder.PROCESSADA;
            case "expired" -> StatusOrder.EXPIRADA;
            case "canceled" -> StatusOrder.CANCELADA;
            case "refunded" -> StatusOrder.REEMBOLSADA;
            default -> StatusOrder.CRIADA;
        };
    }
}
