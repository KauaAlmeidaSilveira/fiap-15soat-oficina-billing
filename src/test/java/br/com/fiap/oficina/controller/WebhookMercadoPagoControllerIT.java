package br.com.fiap.oficina.controller;

import br.com.fiap.oficina.core.usecase.BillingUseCase;
import br.com.fiap.oficina.entrypoint.webhook.AssinaturaWebhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookMercadoPagoControllerIT {

    @Autowired MockMvc mockMvc;
    @MockBean BillingUseCase billingUseCase;

    @Test
    @DisplayName("Notificação de order sem login consulta a order")
    void processaOrder() throws Exception {
        mockMvc.perform(post("/webhooks/mercado-pago").param("type", "order").param("data.id", "ORD1"))
                .andExpect(status().isOk());

        verify(billingUseCase).processarNotificacaoOrder("ORD1");
    }

    @Test
    @DisplayName("Assinatura correta é aceita")
    void assinaturaCorreta() throws Exception {
        String v1 = AssinaturaWebhook.hmac("id:ord1;request-id:req;ts:100;", "segredo-de-teste");

        mockMvc.perform(post("/webhooks/mercado-pago").param("type", "order").param("data.id", "ORD1")
                        .header("x-signature", "ts=100,v1=" + v1).header("x-request-id", "req"))
                .andExpect(status().isOk());

        verify(billingUseCase).processarNotificacaoOrder("ORD1");
    }

    @Test
    @DisplayName("Assinatura errada ou malformada responde 401 e não consulta nada")
    void assinaturaErrada() throws Exception {
        mockMvc.perform(post("/webhooks/mercado-pago").param("type", "order").param("data.id", "ORD1")
                        .header("x-signature", "ts=100,v1=errada").header("x-request-id", "req"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/webhooks/mercado-pago").param("type", "order").param("data.id", "ORD1")
                        .header("x-signature", "lixo").header("x-request-id", "req"))
                .andExpect(status().isUnauthorized());

        verify(billingUseCase, never()).processarNotificacaoOrder(any());
    }

    @Test
    @DisplayName("Outros tópicos são confirmados e ignorados")
    void outrosTopicos() throws Exception {
        mockMvc.perform(post("/webhooks/mercado-pago").param("type", "payment").param("data.id", "1"))
                .andExpect(status().isOk());

        verify(billingUseCase, never()).processarNotificacaoOrder(any());
    }
}
