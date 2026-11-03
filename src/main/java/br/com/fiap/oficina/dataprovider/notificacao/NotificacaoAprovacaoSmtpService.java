package br.com.fiap.oficina.dataprovider.notificacao;

import br.com.fiap.oficina.core.gateway.MetricasGateway;
import br.com.fiap.oficina.core.gateway.NotificacaoAprovacaoGateway;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@Profile("default")
@RequiredArgsConstructor
public class NotificacaoAprovacaoSmtpService implements NotificacaoAprovacaoGateway {

    private static final String INTEGRACAO = "smtp";

    private final JavaMailSender mailSender;
    private final MetricasGateway metricasGateway;

    @Value("${spring.mail.username:}")
    private String remetente;

    @Override
    @Async
    public void notificar(Dados dados, String linkAprovar, String linkRecusar) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(remetente);
            helper.setTo(dados.clienteEmail());
            helper.setSubject(AprovacaoEmailTemplate.assunto(dados));
            helper.setText(
                    AprovacaoEmailTemplate.corpo(dados, linkAprovar, linkRecusar),
                    AprovacaoEmailTemplate.corpoHtml(dados, linkAprovar, linkRecusar));

            mailSender.send(mimeMessage);
            log.info("E-mail de aprovação enviado para {} (OS {})", dados.clienteEmail(), dados.numeroOS());
        } catch (MessagingException e) {
            log.warn("Falha ao montar e-mail de aprovação para {}: {}", dados.clienteEmail(), e.getMessage());
            metricasGateway.falhaDeIntegracao(INTEGRACAO, e.getClass().getSimpleName());
        } catch (MailException e) {
            log.error("Falha ao enviar e-mail de aprovação para {} (OS {}): {}",
                    dados.clienteEmail(), dados.numeroOS(), e.getMessage());
            metricasGateway.falhaDeIntegracao(INTEGRACAO, e.getClass().getSimpleName());
        }
    }

    @Override
    public void notificarCobranca(String numeroOS, String clienteNome, String clienteEmail, BigDecimal valor,
                                  String qrData, Instant expiraEm) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(remetente);
            helper.setTo(clienteEmail);
            helper.setSubject(CobrancaEmailTemplate.assuntoCobranca(numeroOS));
            helper.setText(
                    CobrancaEmailTemplate.corpoCobranca(numeroOS, clienteNome, valor, qrData, expiraEm),
                    CobrancaEmailTemplate.corpoCobrancaHtml(numeroOS, clienteNome, valor, qrData, expiraEm));
            helper.addInline(CobrancaEmailTemplate.CID_QR, new ByteArrayResource(QrCodePng.gerar(qrData)), "image/png");
            mailSender.send(mimeMessage);
            log.info("E-mail de cobrança enviado para {} (OS {})", clienteEmail, numeroOS);
        } catch (MessagingException e) {
            throw new IllegalStateException("Falha ao montar o e-mail de cobrança da OS " + numeroOS, e);
        }
    }

    @Override
    public void notificarCancelamento(String numeroOS, String clienteNome, String clienteEmail, String motivo) {
        SimpleMailMessage mensagem = new SimpleMailMessage();
        mensagem.setFrom(remetente);
        mensagem.setTo(clienteEmail);
        mensagem.setSubject(CobrancaEmailTemplate.assuntoCancelamento(numeroOS));
        mensagem.setText(CobrancaEmailTemplate.corpoCancelamento(numeroOS, clienteNome, motivo));
        mailSender.send(mensagem);
        log.info("Aviso de cancelamento enviado para {} (OS {})", clienteEmail, numeroOS);
    }
}
