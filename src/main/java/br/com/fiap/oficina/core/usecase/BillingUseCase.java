package br.com.fiap.oficina.core.usecase;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import br.com.fiap.oficina.core.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.oficina.core.domain.exception.RegraDeNegocioException;
import br.com.fiap.oficina.core.domain.mensageria.ContextoSaga;
import br.com.fiap.oficina.core.domain.mensageria.EventoSaida;
import br.com.fiap.oficina.core.gateway.BillingGateway;
import br.com.fiap.oficina.core.gateway.BillingGateway.Alteracao;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.Order;
import br.com.fiap.oficina.core.gateway.MercadoPagoGateway.StatusOrder;
import br.com.fiap.oficina.core.gateway.MetricasGateway;
import br.com.fiap.oficina.core.gateway.NotificacaoAprovacaoGateway;
import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import br.com.fiap.oficina.core.gateway.TokenAprovacaoGateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class BillingUseCase {

    public static final String ORIGEM_CLIENTE = "CLIENTE";
    public static final String ORIGEM_OFICINA = "OFICINA";
    static final String FALHA_GERAR_ORCAMENTO = "GERAR_ORCAMENTO";
    static final String FALHA_GERAR_COBRANCA = "GERAR_COBRANCA";
    private static final int TENTATIVAS_EMAIL_QR = 3;

    private static final System.Logger LOG = System.getLogger(BillingUseCase.class.getName());

    public record Configuracao(String publicBaseUrl, Duration validadeOrcamento, Duration validadeQr) {}

    private final BillingGateway billing;
    private final MercadoPagoGateway mercadoPago;
    private final NotificacaoAprovacaoGateway notificacao;
    private final TokenAprovacaoGateway tokens;
    private final MetricasGateway metricas;
    private final SimuladorFalhasGateway simulador;
    private final Configuracao configuracao;
    private final Clock clock;

    public BillingUseCase(BillingGateway billing, MercadoPagoGateway mercadoPago, NotificacaoAprovacaoGateway notificacao,
                          TokenAprovacaoGateway tokens, MetricasGateway metricas, SimuladorFalhasGateway simulador,
                          Configuracao configuracao, Clock clock) {
        this.billing = billing;
        this.mercadoPago = mercadoPago;
        this.notificacao = notificacao;
        this.tokens = tokens;
        this.metricas = metricas;
        this.simulador = simulador;
        this.configuracao = configuracao;
        this.clock = clock;
    }

    // --- Orçamento ---

    public void gerarOrcamento(ContextoSaga ctx, Orcamento dados) {
        if (simulador.deveFalhar(FALHA_GERAR_ORCAMENTO)) {
            LOG.log(System.Logger.Level.WARNING, "Falha simulada ao gerar o orçamento da OS " + ctx.osId());
            billing.gravar(Alteracao.soEvento(evento("OrcamentoFalhou", ctx, Map.of("motivo", "Falha simulada")), ctx.causationId()));
            return;
        }
        Optional<Orcamento> existente = billing.buscarOrcamento(ctx.osId());
        if (existente.isPresent()) {
            billing.gravar(Alteracao.soEvento(orcamentoGerado(ctx, existente.get()), ctx.causationId()));
            return;
        }
        Orcamento orcamento = dados.toBuilder()
                .id(UUID.randomUUID())
                .osId(ctx.osId())
                .sagaId(ctx.sagaId())
                .correlationId(ctx.correlationId())
                .status(StatusOrcamento.ENVIADO)
                .expiraEm(clock.instant().plus(configuracao.validadeOrcamento()))
                .build();
        if (billing.gravar(Alteracao.deOrcamento(orcamento, null, orcamentoGerado(ctx, orcamento), ctx.causationId()))) {
            enviarOrcamento(orcamento);
            LOG.log(System.Logger.Level.INFO, "Orçamento " + orcamento.getId() + " gerado para a OS " + ctx.osId());
        }
    }

    /** @return true se aprovado; decisão repetida vira RegraDeNegocioException, respondida com 409 pelo controller. */
    public boolean processarDecisaoViaToken(String token) {
        var decodificado = tokens.validar(token);
        Orcamento orcamento = billing.buscarOrcamento(decodificado.osId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Orçamento da OS", decodificado.osId()));
        ContextoSaga ctx = new ContextoSaga(orcamento.getSagaId(), orcamento.getOsId(), orcamento.getCorrelationId(), null);
        if (!decidir(ctx, orcamento, decodificado.aprovado(), ORIGEM_CLIENTE, UUID.randomUUID())) {
            throw new RegraDeNegocioException("Orçamento da OS " + orcamento.getOsId() + " já foi processado.");
        }
        return decodificado.aprovado();
    }

    public void registrarDecisao(ContextoSaga ctx, boolean aprovado) {
        Orcamento orcamento = buscarOrcamento(ctx.osId());
        if (!decidir(ctx, orcamento, aprovado, ORIGEM_OFICINA, ctx.causationId())) {
            LOG.log(System.Logger.Level.INFO, "Decisão da oficina ignorada: orçamento da OS " + ctx.osId() + " já decidido");
        }
    }

    public void expirarOrcamento(ContextoSaga ctx) {
        Orcamento orcamento = buscarOrcamento(ctx.osId());
        Orcamento expirado = orcamento.toBuilder().status(StatusOrcamento.EXPIRADO).build();
        boolean gravou = billing.gravar(Alteracao.deOrcamento(expirado, StatusOrcamento.ENVIADO,
                evento("OrcamentoExpirado", ctx, Map.of("orcamentoId", orcamento.getId().toString())), ctx.causationId()));
        if (!gravou) {
            LOG.log(System.Logger.Level.INFO, "Expiração ignorada: orçamento da OS " + ctx.osId() + " já decidido");
        }
    }

    public void cancelarOrcamento(ContextoSaga ctx, String motivo, boolean avisarCliente) {
        Optional<Orcamento> encontrado = billing.buscarOrcamento(ctx.osId());
        if (encontrado.isEmpty()) {
            billing.gravar(Alteracao.soEvento(evento("OrcamentoCancelado", ctx, Map.of()), ctx.causationId()));
            return;
        }
        Orcamento orcamento = encontrado.get();
        EventoSaida resposta = evento("OrcamentoCancelado", ctx, Map.of("orcamentoId", orcamento.getId().toString()));
        if (orcamento.getStatus() == StatusOrcamento.CANCELADO) {
            billing.gravar(Alteracao.soEvento(resposta, ctx.causationId()));
            return;
        }
        Orcamento cancelado = orcamento.toBuilder().status(StatusOrcamento.CANCELADO).build();
        if (!billing.gravar(Alteracao.deOrcamento(cancelado, orcamento.getStatus(), resposta, ctx.causationId()))) {
            throw new IllegalStateException("Orçamento da OS " + ctx.osId() + " mudou durante o cancelamento");
        }
        if (avisarCliente) {
            avisarCancelamento(orcamento, motivo);
        }
    }

    public Orcamento buscarOrcamento(Long osId) {
        return billing.buscarOrcamento(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Orçamento da OS", osId));
    }

    // --- Cobrança ---

    public void gerarCobranca(ContextoSaga ctx, UUID orcamentoId, BigDecimal valor) {
        Optional<Cobranca> existente = billing.buscarCobranca(ctx.osId());
        if (existente.isPresent()) {
            responderCobrancaExistente(ctx, existente.get());
            return;
        }
        if (simulador.deveFalhar(FALHA_GERAR_COBRANCA)) {
            billing.gravar(Alteracao.soEvento(evento("CobrancaFalhou", ctx, Map.of("motivo", "Falha simulada")), ctx.causationId()));
            return;
        }
        Orcamento orcamento = buscarOrcamento(ctx.osId());
        UUID cobrancaId = UUID.nameUUIDFromBytes(("cobranca-" + ctx.sagaId()).getBytes(StandardCharsets.UTF_8));
        Order order;
        try {
            order = mercadoPago.criarOrder(cobrancaId.toString(), String.valueOf(ctx.osId()), valor, configuracao.validadeQr());
        } catch (MercadoPagoGateway.FalhaMercadoPagoException e) {
            LOG.log(System.Logger.Level.ERROR, "Falha ao criar cobrança da OS " + ctx.osId() + ": " + e.getMessage());
            metricas.falhaDeIntegracao("mercado-pago", e.getClass().getSimpleName());
            billing.gravar(Alteracao.soEvento(evento("CobrancaFalhou", ctx, Map.of("motivo", "Mercado Pago indisponível")), ctx.causationId()));
            return;
        }
        Instant expiraEm = clock.instant().plus(configuracao.validadeQr());
        Cobranca cobranca = Cobranca.builder()
                .id(cobrancaId)
                .osId(ctx.osId())
                .sagaId(ctx.sagaId())
                .correlationId(ctx.correlationId())
                .orcamentoId(orcamentoId)
                .status(StatusCobranca.CRIADA)
                .valor(valor)
                .mpOrderId(order.id())
                .qrData(order.qrData())
                .expiraEm(expiraEm)
                .build();
        EventoSaida resposta = enviarQr(orcamento, cobranca)
                ? cobrancaGerada(ctx, cobranca)
                : evento("CobrancaFalhou", ctx, Map.of("motivo", "Falha ao enviar o QR por e-mail"));
        // Mesmo com falha no e-mail a cobrança é gravada: a order existe e precisa ser encerrada.
        billing.gravar(Alteracao.deCobranca(cobranca, null, resposta, ctx.causationId()));
        LOG.log(System.Logger.Level.INFO, "Cobrança " + order.id() + " da OS " + ctx.osId() + ": " + resposta.tipo());
    }

    public void encerrarCobranca(ContextoSaga ctx, String motivo) {
        Optional<Cobranca> encontrada = billing.buscarCobranca(ctx.osId());
        if (encontrada.isEmpty()) {
            Cobranca bloqueada = Cobranca.builder().id(UUID.randomUUID()).osId(ctx.osId()).sagaId(ctx.sagaId())
                    .correlationId(ctx.correlationId()).status(StatusCobranca.BLOQUEADA).build();
            billing.gravar(Alteracao.deCobranca(bloqueada, null, pagamentoFalhou(ctx, "CANCELADO"), ctx.causationId()));
            return;
        }
        Cobranca cobranca = encontrada.get();
        switch (cobranca.getStatus()) {
            case CRIADA -> encerrarOrder(ctx, cobranca, motivo);
            case PAGA -> billing.gravar(Alteracao.soEvento(pagamentoConfirmado(ctx, cobranca), ctx.causationId()));
            default -> billing.gravar(Alteracao.soEvento(pagamentoFalhou(ctx, "CANCELADO"), ctx.causationId()));
        }
    }

    /** Webhook: o corpo só diz qual order consultar; quem decide é a resposta da API do Mercado Pago. */
    public void processarNotificacaoOrder(String orderId) {
        Order order = mercadoPago.consultarOrder(orderId);
        Long osId;
        try {
            osId = Long.valueOf(order.externalReference());
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.WARNING, "Order " + orderId + " com referência desconhecida: " + order.externalReference());
            return;
        }
        Optional<Cobranca> encontrada = billing.buscarCobranca(osId).filter(c -> orderId.equals(c.getMpOrderId()));
        if (encontrada.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Order " + orderId + " sem cobrança na OS " + osId);
            return;
        }
        Cobranca cobranca = encontrada.get();
        ContextoSaga ctx = new ContextoSaga(cobranca.getSagaId(), osId, cobranca.getCorrelationId(), null);
        aplicarStatusOrder(ctx, cobranca, order, UUID.randomUUID());
    }

    public void estornarPagamento(ContextoSaga ctx) {
        Cobranca cobranca = billing.buscarCobranca(ctx.osId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Cobrança da OS", ctx.osId()));
        if (cobranca.getStatus() == StatusCobranca.ESTORNADA) {
            billing.gravar(Alteracao.soEvento(pagamentoEstornado(ctx, cobranca), ctx.causationId()));
            return;
        }
        try {
            mercadoPago.reembolsarOrder(cobranca.getMpOrderId(), "estorno-" + cobranca.getId());
        } catch (MercadoPagoGateway.FalhaMercadoPagoException e) {
            LOG.log(System.Logger.Level.ERROR, "Falha ao reembolsar a OS " + ctx.osId() + ": " + e.getMessage());
            metricas.falhaDeIntegracao("mercado-pago", e.getClass().getSimpleName());
            billing.gravar(Alteracao.soEvento(evento("EstornoFalhou", ctx, Map.of("motivo", "Mercado Pago recusou ou está indisponível")), ctx.causationId()));
            return;
        }
        Cobranca estornada = cobranca.toBuilder().status(StatusCobranca.ESTORNADA).build();
        billing.gravar(Alteracao.deCobranca(estornada, cobranca.getStatus(), pagamentoEstornado(ctx, cobranca), ctx.causationId()));
        LOG.log(System.Logger.Level.INFO, "Pagamento da OS " + ctx.osId() + " reembolsado");
    }

    public Cobranca buscarCobranca(Long osId) {
        return billing.buscarCobranca(osId)
                .filter(c -> c.getStatus() != StatusCobranca.BLOQUEADA)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Cobrança da OS", osId));
    }

    // --- Apoio ---

    private boolean decidir(ContextoSaga ctx, Orcamento orcamento, boolean aprovado, String origem, UUID mensagemId) {
        Instant agora = clock.instant();
        Orcamento decidido = orcamento.toBuilder()
                .status(aprovado ? StatusOrcamento.APROVADO : StatusOrcamento.RECUSADO)
                .origemDecisao(origem)
                .decididoEm(agora)
                .build();
        EventoSaida resposta = evento(aprovado ? "OrcamentoAprovado" : "OrcamentoRecusado", ctx, Map.of(
                "orcamentoId", orcamento.getId().toString(), "origem", origem, "decididoEm", agora.toString()));
        return billing.gravar(Alteracao.deOrcamento(decidido, StatusOrcamento.ENVIADO, resposta, mensagemId));
    }

    private void encerrarOrder(ContextoSaga ctx, Cobranca cobranca, String motivo) {
        Order order = mercadoPago.consultarOrder(cobranca.getMpOrderId());
        if (order.status() == StatusOrder.CRIADA) {
            try {
                mercadoPago.cancelarOrder(order.id());
                LOG.log(System.Logger.Level.INFO, "Order " + order.id() + " da OS " + ctx.osId() + " cancelada: " + motivo);
                encerrarSeCriada(ctx, cobranca, "CANCELADO", ctx.causationId());
                return;
            } catch (MercadoPagoGateway.CancelamentoRecusadoException e) {
                // A order saiu de created entre a consulta e o cancelamento: o status novo decide.
                order = mercadoPago.consultarOrder(cobranca.getMpOrderId());
            }
        }
        aplicarStatusOrder(ctx, cobranca, order, ctx.causationId());
    }

    private void aplicarStatusOrder(ContextoSaga ctx, Cobranca cobranca, Order order, UUID mensagemId) {
        switch (order.status()) {
            case PROCESSADA -> {
                // Inclui cobrança ENCERRADA: pagamento tardio precisa chegar à Saga para ser reembolsado (D19).
                if (cobranca.getStatus() != StatusCobranca.CRIADA && cobranca.getStatus() != StatusCobranca.ENCERRADA) {
                    return;
                }
                Cobranca paga = cobranca.toBuilder().status(StatusCobranca.PAGA).paymentId(order.paymentId())
                        .pagoEm(clock.instant()).build();
                billing.gravar(Alteracao.deCobranca(paga, cobranca.getStatus(), pagamentoConfirmado(ctx, paga), mensagemId));
            }
            case EXPIRADA -> encerrarSeCriada(ctx, cobranca, "EXPIRADO", mensagemId);
            case CANCELADA -> encerrarSeCriada(ctx, cobranca, "CANCELADO", mensagemId);
            case CRIADA -> {
                if (order.pagamentoRecusado()) {
                    mercadoPago.cancelarOrder(order.id());
                    encerrarSeCriada(ctx, cobranca, "RECUSADO", mensagemId);
                }
            }
            case REEMBOLSADA -> LOG.log(System.Logger.Level.INFO, "Order " + order.id() + " já reembolsada");
        }
    }

    private void encerrarSeCriada(ContextoSaga ctx, Cobranca cobranca, String motivo, UUID mensagemId) {
        if (cobranca.getStatus() == StatusCobranca.CRIADA) {
            Cobranca encerrada = cobranca.toBuilder().status(StatusCobranca.ENCERRADA).build();
            billing.gravar(Alteracao.deCobranca(encerrada, StatusCobranca.CRIADA, pagamentoFalhou(ctx, motivo), mensagemId));
        }
    }

    private void responderCobrancaExistente(ContextoSaga ctx, Cobranca existente) {
        if (existente.getStatus() == StatusCobranca.BLOQUEADA) {
            LOG.log(System.Logger.Level.WARNING, "GerarCobranca ignorado: cobrança da OS " + ctx.osId() + " já encerrada");
            return;
        }
        billing.gravar(Alteracao.soEvento(cobrancaGerada(ctx, existente), ctx.causationId()));
    }

    private void enviarOrcamento(Orcamento orcamento) {
        try {
            String linkAprovar = configuracao.publicBaseUrl() + "/aprovacao-os?token=" + tokens.gerarToken(orcamento.getOsId(), true);
            String linkRecusar = configuracao.publicBaseUrl() + "/aprovacao-os?token=" + tokens.gerarToken(orcamento.getOsId(), false);
            notificacao.notificar(new NotificacaoAprovacaoGateway.Dados(
                    orcamento.getNumeroOs(), orcamento.getClienteNome(), orcamento.getClienteEmail(),
                    orcamento.getVeiculoMarca(), orcamento.getVeiculoModelo(), orcamento.getVeiculoPlaca(),
                    orcamento.getItens().stream()
                            .map(i -> new NotificacaoAprovacaoGateway.Dados.Item(i.getNomeProduto(), i.getQuantidade(), i.getPrecoUnitario()))
                            .toList(),
                    orcamento.getValorTotal()), linkAprovar, linkRecusar);
        } catch (Exception e) {
            // Best-effort (D20): a oficina ainda pode registrar a decisão por PATCH /aprovar.
            LOG.log(System.Logger.Level.WARNING, "Falha ao enviar o orçamento da OS " + orcamento.getOsId() + ": " + e.getMessage());
            metricas.falhaDeIntegracao("notificacao-aprovacao", e.getClass().getSimpleName());
        }
    }

    private boolean enviarQr(Orcamento orcamento, Cobranca cobranca) {
        for (int tentativa = 1; tentativa <= TENTATIVAS_EMAIL_QR; tentativa++) {
            try {
                notificacao.notificarCobranca(orcamento.getNumeroOs(), orcamento.getClienteNome(), orcamento.getClienteEmail(),
                        cobranca.getValor(), cobranca.getQrData(), cobranca.getExpiraEm());
                return true;
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Tentativa " + tentativa + " de enviar o QR da OS "
                        + orcamento.getOsId() + " falhou: " + e.getMessage());
                metricas.falhaDeIntegracao("notificacao-cobranca", e.getClass().getSimpleName());
            }
        }
        return false;
    }

    private void avisarCancelamento(Orcamento orcamento, String motivo) {
        try {
            notificacao.notificarCancelamento(orcamento.getNumeroOs(), orcamento.getClienteNome(), orcamento.getClienteEmail(), motivo);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Falha ao avisar o cancelamento da OS " + orcamento.getOsId() + ": " + e.getMessage());
            metricas.falhaDeIntegracao("notificacao-cancelamento", e.getClass().getSimpleName());
        }
    }

    private EventoSaida orcamentoGerado(ContextoSaga ctx, Orcamento orcamento) {
        return evento("OrcamentoGerado", ctx, Map.of(
                "orcamentoId", orcamento.getId().toString(),
                "valorTotal", orcamento.getValorTotal(),
                "expiraEm", orcamento.getExpiraEm().toString()));
    }

    private EventoSaida cobrancaGerada(ContextoSaga ctx, Cobranca cobranca) {
        return evento("CobrancaGerada", ctx, Map.of("mpOrderId", cobranca.getMpOrderId(), "expiraEm", cobranca.getExpiraEm().toString()));
    }

    private EventoSaida pagamentoConfirmado(ContextoSaga ctx, Cobranca cobranca) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("mpOrderId", cobranca.getMpOrderId());
        payload.put("paymentId", cobranca.getPaymentId());
        payload.put("valor", cobranca.getValor());
        payload.put("pagoEm", String.valueOf(cobranca.getPagoEm()));
        return evento("PagamentoConfirmado", ctx, payload);
    }

    private EventoSaida pagamentoFalhou(ContextoSaga ctx, String motivo) {
        return evento("PagamentoFalhou", ctx, Map.of("motivo", motivo));
    }

    private EventoSaida pagamentoEstornado(ContextoSaga ctx, Cobranca cobranca) {
        return evento("PagamentoEstornado", ctx, Map.of("mpOrderId", cobranca.getMpOrderId(), "estornadoEm", clock.instant().toString()));
    }

    private EventoSaida evento(String tipo, ContextoSaga ctx, Map<String, Object> payload) {
        return new EventoSaida(tipo, ctx, payload);
    }
}
