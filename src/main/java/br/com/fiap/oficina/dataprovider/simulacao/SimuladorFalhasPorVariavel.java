package br.com.fiap.oficina.dataprovider.simulacao;

import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulacao")
public class SimuladorFalhasPorVariavel implements SimuladorFalhasGateway {

    private final String pontoSimulado;

    public SimuladorFalhasPorVariavel(@Value("${SIMULAR_FALHA:}") String pontoSimulado) {
        this.pontoSimulado = pontoSimulado;
    }

    @Override
    public boolean deveFalhar(String ponto) {
        return ponto.equals(pontoSimulado);
    }
}
