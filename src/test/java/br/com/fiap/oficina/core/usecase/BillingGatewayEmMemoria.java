package br.com.fiap.oficina.core.usecase;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import br.com.fiap.oficina.core.domain.mensageria.EventoSaida;
import br.com.fiap.oficina.core.gateway.BillingGateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Mesma regra das escritas condicionais do DynamoDB, coberta contra o real em BillingFluxoIT. */
class BillingGatewayEmMemoria implements BillingGateway {

    final Map<Long, Orcamento> orcamentos = new HashMap<>();
    final Map<Long, Cobranca> cobrancas = new HashMap<>();
    final List<EventoSaida> eventos = new ArrayList<>();
    final Set<UUID> inbox = new HashSet<>();

    @Override
    public boolean gravar(Alteracao a) {
        if (a.mensagemId() != null && inbox.contains(a.mensagemId())) {
            return false;
        }
        if (a.orcamento() != null) {
            Orcamento atual = orcamentos.get(a.orcamento().getOsId());
            StatusOrcamento status = atual == null ? null : atual.getStatus();
            if (status != a.orcamentoEsperado()) {
                return false;
            }
        }
        if (a.cobranca() != null) {
            Cobranca atual = cobrancas.get(a.cobranca().getOsId());
            StatusCobranca status = atual == null ? null : atual.getStatus();
            if (status != a.cobrancaEsperada()) {
                return false;
            }
        }
        if (a.orcamento() != null) {
            orcamentos.put(a.orcamento().getOsId(), a.orcamento());
        }
        if (a.cobranca() != null) {
            cobrancas.put(a.cobranca().getOsId(), a.cobranca());
        }
        eventos.addAll(a.eventos());
        if (a.mensagemId() != null) {
            inbox.add(a.mensagemId());
        }
        return true;
    }

    @Override
    public Optional<Orcamento> buscarOrcamento(Long osId) {
        return Optional.ofNullable(orcamentos.get(osId));
    }

    @Override
    public Optional<Cobranca> buscarCobranca(Long osId) {
        return Optional.ofNullable(cobrancas.get(osId));
    }

    @Override
    public boolean mensagemJaProcessada(UUID mensagemId) {
        return inbox.contains(mensagemId);
    }

    List<String> tiposDeEvento() {
        return eventos.stream().map(EventoSaida::tipo).toList();
    }

    EventoSaida ultimoEvento() {
        return eventos.get(eventos.size() - 1);
    }
}
