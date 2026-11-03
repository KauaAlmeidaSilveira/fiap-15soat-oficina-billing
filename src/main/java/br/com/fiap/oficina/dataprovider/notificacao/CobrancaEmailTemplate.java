package br.com.fiap.oficina.dataprovider.notificacao;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class CobrancaEmailTemplate {

    static final String CID_QR = "qr-pagamento";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    private CobrancaEmailTemplate() {}

    static String assuntoCobranca(String numeroOS) {
        return "Pagamento da OS " + numeroOS;
    }

    static String corpoCobranca(String numeroOS, String clienteNome, BigDecimal valor, String qrData, Instant expiraEm) {
        return """
                Olá, %s!

                Seu orçamento da OS %s foi aprovado. Para o serviço começar, pague R$ %s até %s.
                Depois desse prazo, a ordem de serviço será cancelada.

                Código copia-e-cola:
                %s
                """.formatted(clienteNome, numeroOS, valor, DATA.format(expiraEm), qrData);
    }

    static String corpoCobrancaHtml(String numeroOS, String clienteNome, BigDecimal valor, String qrData, Instant expiraEm) {
        return """
                <!DOCTYPE html>
                <html lang="pt-br">
                <head><meta charset="UTF-8"></head>
                <body style="font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;color:#1f2937;">
                  <p>Olá, %s!</p>
                  <p>Seu orçamento da OS <strong>%s</strong> foi aprovado. Para o serviço começar, pague
                  <strong>R$ %s</strong> até <strong>%s</strong>. Depois desse prazo, a ordem de serviço será cancelada.</p>
                  <p><img src="cid:%s" alt="QR Code de pagamento" width="280" height="280"></p>
                  <p>Código copia-e-cola:</p>
                  <p style="word-break:break-all;font-family:monospace;">%s</p>
                </body>
                </html>
                """.formatted(clienteNome, numeroOS, valor, DATA.format(expiraEm), CID_QR, qrData);
    }

    static String assuntoCancelamento(String numeroOS) {
        return "OS " + numeroOS + " cancelada";
    }

    static String corpoCancelamento(String numeroOS, String clienteNome, String motivo) {
        return """
                Olá, %s!

                A sua Ordem de Serviço %s foi cancelada. Motivo: %s.
                Se houve pagamento, o valor será devolvido integralmente.
                """.formatted(clienteNome, numeroOS, motivo);
    }
}
