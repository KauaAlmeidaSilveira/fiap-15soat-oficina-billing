package br.com.fiap.oficina.dataprovider.mensageria;

import com.newrelic.api.agent.ConcurrentHashMapHeaders;
import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransactionNamePriority;
import com.newrelic.api.agent.TransportType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Leva o trace distribuído do New Relic através do SQS. O agente liga sozinho as chamadas HTTP, mas não
 * as mensagens: ao gerar o envelope (ainda dentro da requisição ou da mensagem que o originou) os
 * cabeçalhos de trace vão no campo {@code rastreio}; o listener os aceita e o trace continua do outro
 * lado da fila. Sem o agente (testes e ambiente local) a API do New Relic não faz nada.
 */
public final class RastreioDistribuido {

    private RastreioDistribuido() {}

    /** Cabeçalhos do trace atual, ou {@code null} se não houver transação do New Relic. */
    public static Map<String, String> capturar() {
        try {
            Headers cabecalhos = ConcurrentHashMapHeaders.build(HeaderType.MESSAGE);
            NewRelic.getAgent().getTransaction().insertDistributedTraceHeaders(cabecalhos);
            Map<String, String> rastreio = new LinkedHashMap<>();
            for (String nome : cabecalhos.getHeaderNames()) {
                rastreio.put(nome, cabecalhos.getHeader(nome));
            }
            return rastreio.isEmpty() ? null : rastreio;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Nomeia a transação da mensagem e a liga ao trace de quem a publicou. */
    public static void continuar(String fila, String tipo, Map<String, String> rastreio) {
        try {
            Transaction transacao = NewRelic.getAgent().getTransaction();
            transacao.setTransactionName(TransactionNamePriority.FRAMEWORK_HIGH, true, "Message", "SQS/" + fila + "/" + tipo);
            if (rastreio != null && !rastreio.isEmpty()) {
                transacao.acceptDistributedTraceHeaders(TransportType.Queue,
                        ConcurrentHashMapHeaders.buildFromFlatMap(HeaderType.MESSAGE, rastreio));
            }
        } catch (RuntimeException e) {
            // Observabilidade nunca derruba o consumo da mensagem.
        }
    }
}
