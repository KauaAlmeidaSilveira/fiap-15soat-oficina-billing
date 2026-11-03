package br.com.fiap.oficina.dataprovider.simulacao;

import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Só existe no profile simulacao: provoca falhas de negócio nos testes e no vídeo, nunca em produção. */
@Component
@Profile("simulacao")
public class SimuladorFalhasPorVariavel implements SimuladorFalhasGateway {

    private volatile String pontoSimulado;

    public SimuladorFalhasPorVariavel(@Value("${SIMULAR_FALHA:}") String pontoSimulado) {
        this.pontoSimulado = pontoSimulado;
    }

    @Override
    public boolean deveFalhar(String ponto) {
        return ponto.equals(pontoSimulado);
    }

    public void simular(String ponto) {
        pontoSimulado = ponto;
    }
}
