package br.com.fiap.oficina.core.gateway;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import br.com.fiap.oficina.core.domain.mensageria.EventoSaida;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingGateway {

    /**
     * Grava tudo ou nada: orçamento e/ou cobrança, eventos de saída e o ID da mensagem processada.
     * {@code statusEsperado == null} exige que o registro ainda não exista.
     */
    record Alteracao(Orcamento orcamento, StatusOrcamento orcamentoEsperado,
                     Cobranca cobranca, StatusCobranca cobrancaEsperada,
                     List<EventoSaida> eventos, UUID mensagemId) {

        public static Alteracao deOrcamento(Orcamento orcamento, StatusOrcamento esperado, EventoSaida evento, UUID mensagemId) {
            return new Alteracao(orcamento, esperado, null, null, List.of(evento), mensagemId);
        }

        public static Alteracao deCobranca(Cobranca cobranca, StatusCobranca esperada, EventoSaida evento, UUID mensagemId) {
            return new Alteracao(null, null, cobranca, esperada, List.of(evento), mensagemId);
        }

        public static Alteracao soEvento(EventoSaida evento, UUID mensagemId) {
            return new Alteracao(null, null, null, null, List.of(evento), mensagemId);
        }
    }

    /** @return false se o estado mudou antes da gravação (quem chegou primeiro venceu). */
    boolean gravar(Alteracao alteracao);

    Optional<Orcamento> buscarOrcamento(Long osId);

    Optional<Cobranca> buscarCobranca(Long osId);

    boolean mensagemJaProcessada(UUID mensagemId);
}
