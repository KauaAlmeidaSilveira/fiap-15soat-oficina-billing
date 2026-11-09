package br.com.fiap.oficina.core.gateway;

import java.math.BigDecimal;
import java.time.Duration;

public interface MercadoPagoGateway {

    enum StatusOrder { CRIADA, PROCESSADA, EXPIRADA, CANCELADA, REEMBOLSADA }

    record Order(String id, StatusOrder status, String externalReference, String qrData,
                 String paymentId, boolean pagamentoRecusado) {}

    /** Falha de integração depois de esgotar as tentativas. */
    class FalhaMercadoPagoException extends RuntimeException {
        public FalhaMercadoPagoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    /** O Mercado Pago só cancela order em {@code created}. */
    class CancelamentoRecusadoException extends RuntimeException {
        public CancelamentoRecusadoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    Order criarOrder(String chaveIdempotencia, String externalReference, BigDecimal valor, Duration validade);

    Order consultarOrder(String orderId);

    void cancelarOrder(String orderId);

    void reembolsarOrder(String orderId, String chaveIdempotencia);
}
