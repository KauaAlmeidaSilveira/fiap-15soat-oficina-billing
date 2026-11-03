package br.com.fiap.oficina.core.gateway;

public interface MetricasGateway {

    void falhaDeIntegracao(String integracao, String motivo);
}
