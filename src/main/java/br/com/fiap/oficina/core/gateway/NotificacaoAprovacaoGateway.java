package br.com.fiap.oficina.core.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface NotificacaoAprovacaoGateway {

    void notificar(Dados dados, String linkAprovar, String linkRecusar);

    /** @throws RuntimeException se o e-mail não puder ser enviado: sem ele o cliente não tem como pagar (D20). */
    void notificarCobranca(String numeroOS, String clienteNome, String clienteEmail, BigDecimal valor,
                           String qrData, Instant expiraEm);

    void notificarCancelamento(String numeroOS, String clienteNome, String clienteEmail, String motivo);

    /**
     * Dados já materializados (não-JPA) para não vazar a Session do Hibernate para uma
     * eventual thread assíncrona — ver NotificacaoAprovacaoSmtpService.
     */
    record Dados(
            String numeroOS,
            String clienteNome,
            String clienteEmail,
            String veiculoMarca,
            String veiculoModelo,
            String veiculoPlaca,
            List<Item> itens,
            BigDecimal valorTotal
    ) {
        public record Item(String nomeProduto, Integer quantidade, BigDecimal precoUnitario) {}
    }
}
