package br.com.fiap.oficina.entrypoint.mensageria;

import br.com.fiap.oficina.dataprovider.mensageria.Envelope;
import br.com.fiap.oficina.entrypoint.filter.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mensageria.habilitada", havingValue = "true")
@RequiredArgsConstructor
public class ComandosListener {

    private final ProcessadorComandos processador;
    private final ObjectMapper objectMapper;

    @SqsListener("${app.mensageria.fila-comandos}")
    public void receber(String corpo) throws JsonProcessingException {
        Envelope comando = objectMapper.readValue(corpo, Envelope.class);
        MDC.put(CorrelationIdFilter.MDC_KEY, comando.correlationId());
        MDC.put("sagaId", String.valueOf(comando.sagaId()));
        try {
            processador.processar(comando);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            MDC.remove("sagaId");
        }
    }
}
