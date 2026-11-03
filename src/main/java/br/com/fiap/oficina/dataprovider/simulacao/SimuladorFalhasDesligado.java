package br.com.fiap.oficina.dataprovider.simulacao;

import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!simulacao")
public class SimuladorFalhasDesligado implements SimuladorFalhasGateway {

    @Override
    public boolean deveFalhar(String ponto) {
        return false;
    }
}
