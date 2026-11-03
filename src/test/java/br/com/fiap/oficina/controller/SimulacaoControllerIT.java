package br.com.fiap.oficina.controller;

import br.com.fiap.oficina.core.gateway.SimuladorFalhasGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "simulacao"})
class SimulacaoControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired SimuladorFalhasGateway simulador;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin liga e desliga a falha simulada")
    void ligaEDesliga() throws Exception {
        mockMvc.perform(put("/api/simulacao/falha").param("ponto", "X")).andExpect(status().isNoContent());
        assertThat(simulador.deveFalhar("X")).isTrue();

        mockMvc.perform(delete("/api/simulacao/falha")).andExpect(status().isNoContent());
        assertThat(simulador.deveFalhar("X")).isFalse();
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Operador não controla a simulação")
    void operadorNaoControla() throws Exception {
        mockMvc.perform(put("/api/simulacao/falha").param("ponto", "X")).andExpect(status().isForbidden());
    }
}
