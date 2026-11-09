package br.com.fiap.oficina.core.usecase;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import br.com.fiap.oficina.core.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.oficina.core.domain.exception.RegraDeNegocioException;
import br.com.fiap.oficina.core.domain.mensageria.ContextoSaga;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.Order;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.StatusOrder;
import br.com.fiap.oficina.core.gateway.MetricasGateway;
import br.com.fiap.oficina.core.gateway.NotificacaoAprovacaoGateway;
import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import br.com.fiap.oficina.core.gateway.TokenAprovacaoGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingUseCaseUnitTest {

    private static final Instant AGORA = Instant.parse("2026-11-03T12:00:00Z");
    private static final Long OS = 7L;
    private static final UUID SAGA = UUID.randomUUID();
    private static final BigDecimal VALOR = new BigDecimal("150.00");

    @Mock MercadoPagoGateway mercadoPago;
    @Mock NotificacaoAprovacaoGateway notificacao;
    @Mock TokenAprovacaoGateway tokens;
    @Mock MetricasGateway metricas;
    @Mock SimuladorFalhasGateway simulador;

    private BillingGatewayEmMemoria gateway;
    private BillingUseCase useCase;

    @BeforeEach
    void setUp() {
        gateway = new BillingGatewayEmMemoria();
        useCase = new BillingUseCase(gateway, mercadoPago, notificacao, tokens, metricas, simulador,
                new BillingUseCase.Configuracao("http://oficina", Duration.ofDays(7), Duration.ofHours(24)),
                Clock.fixed(AGORA, ZoneOffset.UTC));
        when(tokens.gerarToken(any(), eq(true))).thenReturn("sim");
        when(tokens.gerarToken(any(), eq(false))).thenReturn("nao");
    }

    private ContextoSaga ctx() {
        return new ContextoSaga(SAGA, OS, "corr", UUID.randomUUID());
    }

    private Orcamento dados() {
        return Orcamento.builder().numeroOs("OS1").valorTotal(VALOR).clienteNome("Maria").clienteEmail("maria@email.com")
                .veiculoMarca("Toyota").veiculoModelo("Corolla").veiculoPlaca("ABC1234")
                .itens(List.of(new Orcamento.Item("Filtro", 1, VALOR))).build();
    }

    private void comOrcamento() {
        useCase.gerarOrcamento(ctx(), dados());
        gateway.eventos.clear();
    }

    private Order order(StatusOrder status) {
        return new Order("ORD1", status, String.valueOf(OS), "qr-data", "PAY1", false);
    }

    private void comCobranca() {
        comOrcamento();
        when(mercadoPago.criarOrder(anyString(), anyString(), any(), any())).thenReturn(order(StatusOrder.CRIADA));
        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);
        gateway.eventos.clear();
    }

    @Test
    @DisplayName("Deve gerar orçamento com validade de 7 dias e enviar os dois links")
    void deveGerarOrcamento() {
        useCase.gerarOrcamento(ctx(), dados());

        Orcamento orcamento = gateway.orcamentos.get(OS);
        assertThat(orcamento.getStatus()).isEqualTo(StatusOrcamento.ENVIADO);
        assertThat(orcamento.getExpiraEm()).isEqualTo(AGORA.plus(Duration.ofDays(7)));
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoGerado");
        verify(notificacao).notificar(any(), eq("http://oficina/aprovacao-os?token=sim"), eq("http://oficina/aprovacao-os?token=nao"));
    }

    @Test
    @DisplayName("Comando repetido não gera outro orçamento nem outro e-mail")
    void orcamentoRepetido() {
        useCase.gerarOrcamento(ctx(), dados());
        useCase.gerarOrcamento(ctx(), dados());

        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoGerado", "OrcamentoGerado");
        verify(notificacao, times(1)).notificar(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Falha no e-mail do orçamento não interrompe a Saga (D20)")
    void emailDoOrcamentoEhBestEffort() {
        doThrow(new IllegalStateException("smtp")).when(notificacao).notificar(any(), anyString(), anyString());

        useCase.gerarOrcamento(ctx(), dados());

        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoGerado");
        verify(metricas).falhaDeIntegracao("notificacao-aprovacao", "IllegalStateException");
    }

    @Test
    @DisplayName("Falha simulada responde OrcamentoFalhou sem gravar orçamento")
    void falhaSimuladaNoOrcamento() {
        when(simulador.deveFalhar("GERAR_ORCAMENTO")).thenReturn(true);

        useCase.gerarOrcamento(ctx(), dados());

        assertThat(gateway.orcamentos).isEmpty();
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoFalhou");
    }

    @Test
    @DisplayName("Link aprova uma vez; a segunda tentativa é recusada")
    void linkAprovaUmaVez() {
        comOrcamento();
        when(tokens.validar("t")).thenReturn(new TokenAprovacaoGateway.TokenAprovacao(OS, true));

        assertThat(useCase.processarDecisaoViaToken("t")).isTrue();
        assertThat(gateway.orcamentos.get(OS).getStatus()).isEqualTo(StatusOrcamento.APROVADO);
        assertThat(gateway.orcamentos.get(OS).getOrigemDecisao()).isEqualTo("CLIENTE");
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoAprovado");

        assertThatThrownBy(() -> useCase.processarDecisaoViaToken("t")).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("Link de recusa registra a recusa")
    void linkRecusa() {
        comOrcamento();
        when(tokens.validar("t")).thenReturn(new TokenAprovacaoGateway.TokenAprovacao(OS, false));

        assertThat(useCase.processarDecisaoViaToken("t")).isFalse();
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoRecusado");
    }

    @Test
    @DisplayName("Link de OS sem orçamento responde não encontrado")
    void linkSemOrcamento() {
        when(tokens.validar("t")).thenReturn(new TokenAprovacaoGateway.TokenAprovacao(OS, true));

        assertThatThrownBy(() -> useCase.processarDecisaoViaToken("t")).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Decisão da oficina usa o mesmo caminho; depois do cliente ela é ignorada")
    void decisaoDaOficina() {
        comOrcamento();
        useCase.registrarDecisao(ctx(), true);
        assertThat(gateway.orcamentos.get(OS).getOrigemDecisao()).isEqualTo("OFICINA");

        useCase.registrarDecisao(ctx(), false);
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoAprovado");
    }

    @Test
    @DisplayName("Prazo vencido expira o orçamento; decisão anterior vence a expiração (D9)")
    void expiracao() {
        comOrcamento();
        useCase.expirarOrcamento(ctx());
        assertThat(gateway.orcamentos.get(OS).getStatus()).isEqualTo(StatusOrcamento.EXPIRADO);
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoExpirado");

        gateway.orcamentos.get(OS).setStatus(StatusOrcamento.APROVADO);
        useCase.expirarOrcamento(ctx());
        assertThat(gateway.orcamentos.get(OS).getStatus()).isEqualTo(StatusOrcamento.APROVADO);
        assertThat(gateway.tiposDeEvento()).hasSize(1);
    }

    @Test
    @DisplayName("Cancelar avisa o cliente só quando pedido, e é idempotente (D20)")
    void cancelamentoDoOrcamento() {
        comOrcamento();
        useCase.cancelarOrcamento(ctx(), "Estoque insuficiente", true);
        useCase.cancelarOrcamento(ctx(), "Estoque insuficiente", true);

        assertThat(gateway.orcamentos.get(OS).getStatus()).isEqualTo(StatusOrcamento.CANCELADO);
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoCancelado", "OrcamentoCancelado");
        verify(notificacao, times(1)).notificarCancelamento("OS1", "Maria", "maria@email.com", "Estoque insuficiente");
    }

    @Test
    @DisplayName("Cancelar sem aviso, sem orçamento e com falha no aviso sempre responde")
    void cancelamentoSempreResponde() {
        useCase.cancelarOrcamento(ctx(), "Sem orçamento", true);
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoCancelado");

        comOrcamento();
        doThrow(new IllegalStateException("smtp")).when(notificacao).notificarCancelamento(any(), any(), any(), any());
        useCase.cancelarOrcamento(ctx(), "Falha", true);
        assertThat(gateway.tiposDeEvento()).containsExactly("OrcamentoCancelado");
        verify(metricas).falhaDeIntegracao("notificacao-cancelamento", "IllegalStateException");
    }

    @Test
    @DisplayName("Deve gerar a cobrança com validade de 24 h e enviar o QR (D13, D14)")
    void deveGerarCobranca() {
        comOrcamento();
        when(mercadoPago.criarOrder(anyString(), eq("7"), eq(VALOR), eq(Duration.ofHours(24)))).thenReturn(order(StatusOrder.CRIADA));

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        Cobranca cobranca = useCase.buscarCobranca(OS);
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.CRIADA);
        assertThat(cobranca.getExpiraEm()).isEqualTo(AGORA.plus(Duration.ofHours(24)));
        assertThat(gateway.tiposDeEvento()).containsExactly("CobrancaGerada");
        verify(notificacao).notificarCobranca("OS1", "Maria", "maria@email.com", VALOR, "qr-data", cobranca.getExpiraEm());
    }

    @Test
    @DisplayName("Comando repetido não cria outra order")
    void cobrancaRepetida() {
        comCobranca();

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        verify(mercadoPago, times(1)).criarOrder(anyString(), anyString(), any(), any());
        assertThat(gateway.tiposDeEvento()).containsExactly("CobrancaGerada");
    }

    @Test
    @DisplayName("Mercado Pago fora responde CobrancaFalhou")
    void mercadoPagoFora() {
        comOrcamento();
        when(mercadoPago.criarOrder(anyString(), anyString(), any(), any()))
                .thenThrow(new MercadoPagoGateway.FalhaMercadoPagoException("fora", null));

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        assertThat(gateway.tiposDeEvento()).containsExactly("CobrancaFalhou");
        assertThat(gateway.cobrancas).isEmpty();
    }

    @Test
    @DisplayName("Falha simulada na cobrança responde CobrancaFalhou")
    void falhaSimuladaNaCobranca() {
        when(simulador.deveFalhar("GERAR_COBRANCA")).thenReturn(true);

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        assertThat(gateway.tiposDeEvento()).containsExactly("CobrancaFalhou");
    }

    @Test
    @DisplayName("E-mail do QR tem 3 tentativas; depois a cobrança falha, mas fica gravada para ser encerrada (D20)")
    void emailDoQrObrigatorio() {
        comOrcamento();
        when(mercadoPago.criarOrder(anyString(), anyString(), any(), any())).thenReturn(order(StatusOrder.CRIADA));
        doThrow(new IllegalStateException("smtp")).when(notificacao).notificarCobranca(any(), any(), any(), any(), any(), any());

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        verify(notificacao, times(3)).notificarCobranca(any(), any(), any(), any(), any(), any());
        assertThat(gateway.tiposDeEvento()).containsExactly("CobrancaFalhou");
        assertThat(gateway.cobrancas.get(OS).getMpOrderId()).isEqualTo("ORD1");
    }

    @Test
    @DisplayName("Encerrar sem cobrança grava marcador; GerarCobranca atrasado é ignorado")
    void marcadorDeEncerramento() {
        comOrcamento();
        useCase.encerrarCobranca(ctx(), "Prazo esgotado");
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.BLOQUEADA);
        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoFalhou");

        useCase.gerarCobranca(ctx(), UUID.randomUUID(), VALOR);

        verify(mercadoPago, never()).criarOrder(any(), any(), any(), any());
        assertThat(gateway.tiposDeEvento()).hasSize(1);
        assertThatThrownBy(() -> useCase.buscarCobranca(OS)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Encerrar order em created cancela no Mercado Pago")
    void encerrarCancela() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.CRIADA));

        useCase.encerrarCobranca(ctx(), "QR vencido");

        verify(mercadoPago).cancelarOrder("ORD1");
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.ENCERRADA);
        assertThat(gateway.ultimoEvento().payload()).containsEntry("motivo", "CANCELADO");
    }

    @Test
    @DisplayName("Cliente pagou enquanto o QR vencia: encerrar confirma o pagamento")
    void pagouDuranteOEncerramento() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.PROCESSADA));

        useCase.encerrarCobranca(ctx(), "QR vencido");

        verify(mercadoPago, never()).cancelarOrder(any());
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.PAGA);
        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado");
    }

    @Test
    @DisplayName("Conflito no cancelamento consulta de novo e segue o status novo")
    void conflitoNoCancelamento() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.CRIADA), order(StatusOrder.PROCESSADA));
        doThrow(new MercadoPagoGateway.CancelamentoRecusadoException("409", null)).when(mercadoPago).cancelarOrder("ORD1");

        useCase.encerrarCobranca(ctx(), "QR vencido");

        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado");
    }

    @Test
    @DisplayName("Encerrar cobrança já paga ou já encerrada só responde")
    void encerrarRepetido() {
        comCobranca();
        gateway.cobrancas.get(OS).setStatus(StatusCobranca.PAGA);
        useCase.encerrarCobranca(ctx(), "x");
        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado");

        gateway.cobrancas.get(OS).setStatus(StatusCobranca.ENCERRADA);
        useCase.encerrarCobranca(ctx(), "x");
        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado", "PagamentoFalhou");
        verify(mercadoPago, never()).cancelarOrder(any());
    }

    @Test
    @DisplayName("Webhook repetido confirma o pagamento uma vez só")
    void webhookRepetido() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.PROCESSADA));

        useCase.processarNotificacaoOrder("ORD1");
        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado");
        assertThat(gateway.cobrancas.get(OS).getPaymentId()).isEqualTo("PAY1");
    }

    @Test
    @DisplayName("Webhook de QR expirado encerra a cobrança (D2)")
    void webhookExpirado() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.EXPIRADA));

        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.ultimoEvento().tipo()).isEqualTo("PagamentoFalhou");
        assertThat(gateway.ultimoEvento().payload()).containsEntry("motivo", "EXPIRADO");
    }

    @Test
    @DisplayName("Pagamento recusado cancela a order e encerra a cobrança (D2)")
    void pagamentoRecusado() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(new Order("ORD1", StatusOrder.CRIADA, "7", "qr", null, true));

        useCase.processarNotificacaoOrder("ORD1");

        verify(mercadoPago).cancelarOrder("ORD1");
        assertThat(gateway.ultimoEvento().payload()).containsEntry("motivo", "RECUSADO");
    }

    @Test
    @DisplayName("Order em aberto, sem recusa, não muda nada")
    void orderEmAberto() {
        comCobranca();
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.CRIADA));

        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.eventos).isEmpty();
    }

    @Test
    @DisplayName("Pagamento depois do encerramento chega à Saga para ser reembolsado (D19)")
    void pagamentoTardio() {
        comCobranca();
        gateway.cobrancas.get(OS).setStatus(StatusCobranca.ENCERRADA);
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.PROCESSADA));

        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoConfirmado");
    }

    @Test
    @DisplayName("Notificação atrasada não reabre cobrança já estornada")
    void notificacaoDepoisDoEstorno() {
        comCobranca();
        gateway.cobrancas.get(OS).setStatus(StatusCobranca.ESTORNADA);
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.PROCESSADA));

        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.eventos).isEmpty();
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.ESTORNADA);
    }

    @Test
    @DisplayName("Order desconhecida, de outra OS ou reembolsada não altera nada")
    void orderDesconhecida() {
        comCobranca();
        when(mercadoPago.consultarOrder("X")).thenReturn(new Order("X", StatusOrder.PROCESSADA, "abc", null, null, false));
        when(mercadoPago.consultarOrder("Y")).thenReturn(new Order("Y", StatusOrder.PROCESSADA, "7", null, null, false));
        when(mercadoPago.consultarOrder("ORD1")).thenReturn(order(StatusOrder.REEMBOLSADA));

        useCase.processarNotificacaoOrder("X");
        useCase.processarNotificacaoOrder("Y");
        useCase.processarNotificacaoOrder("ORD1");

        assertThat(gateway.eventos).isEmpty();
    }

    @Test
    @DisplayName("Estorno reembolsa uma vez e é idempotente (D19, D22)")
    void estorno() {
        comCobranca();
        gateway.cobrancas.get(OS).setStatus(StatusCobranca.PAGA);

        useCase.estornarPagamento(ctx());
        useCase.estornarPagamento(ctx());

        verify(mercadoPago, times(1)).reembolsarOrder(eq("ORD1"), anyString());
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.ESTORNADA);
        assertThat(gateway.tiposDeEvento()).containsExactly("PagamentoEstornado", "PagamentoEstornado");
    }

    @Test
    @DisplayName("Falha no reembolso responde EstornoFalhou e mantém a cobrança paga")
    void estornoFalhou() {
        comCobranca();
        gateway.cobrancas.get(OS).setStatus(StatusCobranca.PAGA);
        doThrow(new MercadoPagoGateway.FalhaMercadoPagoException("fora", null)).when(mercadoPago).reembolsarOrder(any(), any());

        useCase.estornarPagamento(ctx());

        assertThat(gateway.tiposDeEvento()).containsExactly("EstornoFalhou");
        assertThat(gateway.cobrancas.get(OS).getStatus()).isEqualTo(StatusCobranca.PAGA);
    }

    @Test
    @DisplayName("Estorno sem cobrança é erro, para ir à DLQ")
    void estornoSemCobranca() {
        assertThatThrownBy(() -> useCase.estornarPagamento(ctx())).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Consulta de orçamento inexistente responde não encontrado")
    void consultaInexistente() {
        assertThatThrownBy(() -> useCase.buscarOrcamento(OS)).isInstanceOf(RecursoNaoEncontradoException.class);
    }
}
