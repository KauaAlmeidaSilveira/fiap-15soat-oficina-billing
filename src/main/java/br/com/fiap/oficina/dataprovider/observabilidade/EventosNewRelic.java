package br.com.fiap.oficina.dataprovider.observabilidade;

import com.newrelic.api.agent.NewRelic;

import java.util.Map;

/**
 * Eventos customizados do New Relic ({@code FROM <tipo> SELECT ...} no NRQL), base dos painéis de Saga e
 * de pagamentos. Não dependem do nri-bundle, que leva as métricas do Prometheus e não cabe no node de
 * 1 vCPU. Sem o agente (testes e ambiente local) a chamada não faz nada.
 */
public final class EventosNewRelic {

    private EventosNewRelic() {}

    public static void registrar(String tipo, Map<String, ?> atributos) {
        try {
            NewRelic.getAgent().getInsights().recordCustomEvent(tipo, atributos);
        } catch (RuntimeException e) {
            // Observabilidade nunca interrompe o fluxo de negócio.
        }
    }
}
