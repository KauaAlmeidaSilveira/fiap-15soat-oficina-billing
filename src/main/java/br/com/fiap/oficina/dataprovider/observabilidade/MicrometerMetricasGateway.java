package br.com.fiap.oficina.dataprovider.observabilidade;

import br.com.fiap.oficina.core.gateway.MetricasGateway;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MicrometerMetricasGateway implements MetricasGateway {

    private final MeterRegistry registry;

    @Override
    public void falhaDeIntegracao(String integracao, String motivo) {
        registry.counter("oficina.integracao.falhas", "integracao", integracao, "motivo", motivo).increment();
    }
}
