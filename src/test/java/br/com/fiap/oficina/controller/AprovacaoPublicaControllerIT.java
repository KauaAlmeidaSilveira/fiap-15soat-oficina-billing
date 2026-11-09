package br.com.fiap.oficina.controller;

import br.com.fiap.oficina.core.domain.entity.Cobranca;
import br.com.fiap.oficina.core.domain.entity.Orcamento;
import br.com.fiap.oficina.core.domain.enums.StatusCobranca;
import br.com.fiap.oficina.core.domain.enums.StatusOrcamento;
import br.com.fiap.oficina.core.gateway.BillingGateway;
import br.com.fiap.oficina.core.gateway.TokenAprovacaoGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AprovacaoPublicaControllerIT {

    private static final Long OS = 7L;

    @Autowired MockMvc mockMvc;
    @Autowired TokenAprovacaoGateway tokens;
    @MockBean BillingGateway gateway;

    private Orcamento orcamento() {
        return Orcamento.builder().id(UUID.randomUUID()).osId(OS).sagaId(UUID.randomUUID()).status(StatusOrcamento.ENVIADO)
                .valorTotal(BigDecimal.TEN).build();
    }

    @Test
    @DisplayName("Link de aprovação responde a página de sucesso, sem login, e avisa do QR por e-mail")
    void aprova() throws Exception {
        when(gateway.buscarOrcamento(OS)).thenReturn(Optional.of(orcamento()));
        when(gateway.gravar(any())).thenReturn(true);

        mockMvc.perform(get("/aprovacao-os").param("token", tokens.gerarToken(OS, true)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("QR Code")));
    }

    @Test
    @DisplayName("Link de recusa responde a página de recusa")
    void recusa() throws Exception {
        when(gateway.buscarOrcamento(OS)).thenReturn(Optional.of(orcamento()));
        when(gateway.gravar(any())).thenReturn(true);

        mockMvc.perform(get("/aprovacao-os").param("token", tokens.gerarToken(OS, false)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("recusado")));
    }

    @Test
    @DisplayName("Segunda tentativa responde 409, como na Fase 3")
    void segundaTentativa() throws Exception {
        when(gateway.buscarOrcamento(OS)).thenReturn(Optional.of(orcamento()));
        when(gateway.gravar(any())).thenReturn(false);

        mockMvc.perform(get("/aprovacao-os").param("token", tokens.gerarToken(OS, true)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Token inválido responde 400 e orçamento inexistente responde 404")
    void erros() throws Exception {
        mockMvc.perform(get("/aprovacao-os").param("token", "invalido")).andExpect(status().isBadRequest());

        when(gateway.buscarOrcamento(OS)).thenReturn(Optional.empty());
        mockMvc.perform(get("/aprovacao-os").param("token", tokens.gerarToken(OS, true))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Funcionário consulta orçamento e pagamento")
    void consultas() throws Exception {
        when(gateway.buscarOrcamento(OS)).thenReturn(Optional.of(orcamento()));
        when(gateway.buscarCobranca(OS)).thenReturn(Optional.of(Cobranca.builder().osId(OS).status(StatusCobranca.PAGA).build()));

        mockMvc.perform(get("/api/orcamentos/7")).andExpect(jsonPath("$.status").value("ENVIADO"));
        mockMvc.perform(get("/api/pagamentos/7")).andExpect(jsonPath("$.status").value("PAGA"));
    }

    @Test
    @DisplayName("Consulta exige autenticação")
    void consultaExigeLogin() throws Exception {
        mockMvc.perform(get("/api/orcamentos/7")).andExpect(status().isUnauthorized());
    }
}
