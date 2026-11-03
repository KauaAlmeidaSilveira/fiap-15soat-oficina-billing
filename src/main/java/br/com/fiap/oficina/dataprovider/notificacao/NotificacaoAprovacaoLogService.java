package br.com.fiap.oficina.dataprovider.notificacao;

import br.com.fiap.oficina.core.gateway.NotificacaoAprovacaoGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@Profile({"test", "dev"})
public class NotificacaoAprovacaoLogService implements NotificacaoAprovacaoGateway {

    @Override
    public void notificar(Dados dados, String linkAprovar, String linkRecusar) {
        log.info("--- E-MAIL SIMULADO ---\nPara: {} <{}>\nAssunto: {}\n\n{}",
                dados.clienteNome(), dados.clienteEmail(), AprovacaoEmailTemplate.assunto(dados),
                AprovacaoEmailTemplate.corpo(dados, linkAprovar, linkRecusar));
    }

    @Override
    public void notificarCobranca(String numeroOS, String clienteNome, String clienteEmail, BigDecimal valor,
                                  String qrData, Instant expiraEm) {
        log.info("--- E-MAIL SIMULADO ---\nPara: {} <{}>\nAssunto: {}\n\n{}", clienteNome, clienteEmail,
                CobrancaEmailTemplate.assuntoCobranca(numeroOS),
                CobrancaEmailTemplate.corpoCobranca(numeroOS, clienteNome, valor, qrData, expiraEm));
    }

    @Override
    public void notificarCancelamento(String numeroOS, String clienteNome, String clienteEmail, String motivo) {
        log.info("--- E-MAIL SIMULADO ---\nPara: {} <{}>\nAssunto: {}\n\n{}", clienteNome, clienteEmail,
                CobrancaEmailTemplate.assuntoCancelamento(numeroOS),
                CobrancaEmailTemplate.corpoCancelamento(numeroOS, clienteNome, motivo));
    }
}
