package br.com.fiap.oficina.dataprovider.notificacao;

import br.com.fiap.oficina.core.gateway.MetricasGateway;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CobrancaEmailUnitTest {

    private static final Instant EXPIRA = Instant.parse("2026-11-04T15:00:00Z");

    @Mock JavaMailSender mailSender;
    @Mock MetricasGateway metricas;

    private NotificacaoAprovacaoSmtpService servico;

    @BeforeEach
    void setUp() {
        servico = new NotificacaoAprovacaoSmtpService(mailSender, metricas);
        ReflectionTestUtils.setField(servico, "remetente", "oficina@email.com");
    }

    @Test
    @DisplayName("E-mail de cobrança leva valor, prazo em horário de Brasília, copia-e-cola e imagem do QR (D13)")
    void cobranca() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        servico.notificarCobranca("OS1", "Maria", "maria@email.com", new BigDecimal("150.00"), "qr-copia-e-cola", EXPIRA);

        ArgumentCaptor<MimeMessage> enviado = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(enviado.capture());
        ByteArrayOutputStream bruto = new ByteArrayOutputStream();
        enviado.getValue().writeTo(bruto);
        String mensagem = bruto.toString(StandardCharsets.UTF_8);
        assertThat(enviado.getValue().getSubject()).isEqualTo("Pagamento da OS OS1");
        assertThat(mensagem).contains("image/png").contains("qr-pagamento");
        assertThat(CobrancaEmailTemplate.corpoCobranca("OS1", "Maria", new BigDecimal("150.00"), "qr-copia-e-cola", EXPIRA))
                .contains("R$ 150.00").contains("04/11/2026 12:00").contains("qr-copia-e-cola");
    }

    @Test
    @DisplayName("Falha de envio da cobrança propaga, para contar como tentativa (D20)")
    void cobrancaPropagaFalha() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("smtp")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> servico.notificarCobranca("OS1", "Maria", "maria@email.com", BigDecimal.TEN, "qr", EXPIRA))
                .isInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("Aviso de cancelamento informa o motivo")
    void cancelamento() {
        servico.notificarCancelamento("OS1", "Maria", "maria@email.com", "Estoque insuficiente");

        ArgumentCaptor<SimpleMailMessage> enviado = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(enviado.capture());
        assertThat(enviado.getValue().getTo()).containsExactly("maria@email.com");
        assertThat(enviado.getValue().getText()).contains("Estoque insuficiente");
    }

    @Test
    @DisplayName("Serviço de log simula os dois e-mails sem erro")
    void servicoDeLog() {
        NotificacaoAprovacaoLogService log = new NotificacaoAprovacaoLogService();

        log.notificarCobranca("OS1", "Maria", "maria@email.com", BigDecimal.TEN, "qr", EXPIRA);
        log.notificarCancelamento("OS1", "Maria", "maria@email.com", "Motivo");
    }
}
