package br.com.fiap.oficina.dataprovider.gateway;

import br.com.fiap.oficina.core.gateway.MercadoPagoGateway;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.Order;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.StatusOrder;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MercadoPagoRestGatewayUnitTest {

    private static WireMockServer mp;
    private MercadoPagoRestGateway gateway;

    @BeforeAll
    static void iniciar() {
        mp = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        mp.start();
    }

    @AfterAll
    static void parar() {
        mp.stop();
    }

    @BeforeEach
    void setUp() {
        mp.resetAll();
        gateway = new MercadoPagoRestGateway(mp.baseUrl(), "token", "CAIXA1");
    }

    @Test
    @DisplayName("Cria order dinâmica com idempotência e expiração")
    void criaOrder() {
        mp.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id":"ORD1","status":"created","external_reference":"7","type_response":{"qr_data":"qr"}}""")));

        Order order = gateway.criarOrder("chave", "7", new BigDecimal("150"), Duration.ofHours(24));

        assertThat(order.id()).isEqualTo("ORD1");
        assertThat(order.qrData()).isEqualTo("qr");
        assertThat(order.status()).isEqualTo(StatusOrder.CRIADA);
        mp.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withHeader("X-Idempotency-Key", equalTo("chave"))
                .withHeader("Authorization", equalTo("Bearer token"))
                .withRequestBody(equalToJson("""
                        {"type":"qr","external_reference":"7","total_amount":"150.00","expiration_time":"PT24H",
                         "config":{"qr":{"external_pos_id":"CAIXA1","mode":"dynamic"}},
                         "transactions":{"payments":[{"amount":"150.00"}]}}""")));
    }

    @Test
    @DisplayName("Traduz os status e identifica pagamento recusado")
    void traduzStatus() {
        for (var par : new String[][]{{"processed", "PROCESSADA"}, {"expired", "EXPIRADA"}, {"canceled", "CANCELADA"}, {"refunded", "REEMBOLSADA"}}) {
            mp.stubFor(get("/v1/orders/" + par[0]).willReturn(okJson("{\"id\":\"1\",\"status\":\"" + par[0] + "\",\"qr_data\":\"qr\"}")));
            assertThat(gateway.consultarOrder(par[0]).status().name()).isEqualTo(par[1]);
        }
        mp.stubFor(get("/v1/orders/rec").willReturn(okJson("""
                {"id":"rec","status":"created","transactions":{"payments":[{"id":"P1","status":"failed"}]}}""")));
        assertThat(gateway.consultarOrder("rec").pagamentoRecusado()).isTrue();
        assertThat(gateway.consultarOrder("rec").paymentId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Erro 5xx tenta 3 vezes; 4xx falha na primeira")
    void tentativas() {
        mp.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> gateway.criarOrder("c", "7", BigDecimal.TEN, Duration.ofHours(1)))
                .isInstanceOf(MercadoPagoGateway.FalhaMercadoPagoException.class);
        mp.verify(3, postRequestedFor(urlEqualTo("/v1/orders")));

        mp.resetAll();
        mp.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> gateway.criarOrder("c", "7", BigDecimal.TEN, Duration.ofHours(1)))
                .isInstanceOf(MercadoPagoGateway.FalhaMercadoPagoException.class);
        mp.verify(1, postRequestedFor(urlEqualTo("/v1/orders")));
    }

    @Test
    @DisplayName("Cancelamento distingue conflito de falha")
    void cancelamento() {
        mp.stubFor(post("/v1/orders/ok/cancel").willReturn(okJson("{}")));
        gateway.cancelarOrder("ok");

        mp.stubFor(post("/v1/orders/conflito/cancel").willReturn(aResponse().withStatus(409)));
        assertThatThrownBy(() -> gateway.cancelarOrder("conflito")).isInstanceOf(MercadoPagoGateway.CancelamentoRecusadoException.class);

        mp.stubFor(post("/v1/orders/erro/cancel").willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> gateway.cancelarOrder("erro")).isInstanceOf(MercadoPagoGateway.FalhaMercadoPagoException.class);
    }

    @Test
    @DisplayName("Reembolso envia chave de idempotência")
    void reembolso() {
        mp.stubFor(post("/v1/orders/ORD1/refund").willReturn(okJson("{}")));

        gateway.reembolsarOrder("ORD1", "estorno-1");

        mp.verify(postRequestedFor(urlEqualTo("/v1/orders/ORD1/refund")).withHeader("X-Idempotency-Key", equalTo("estorno-1")));
    }
}
