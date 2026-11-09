package br.com.fiap.oficina.entrypoint.controller;

import br.com.fiap.oficina.core.usecase.BillingUseCase;
import br.com.fiap.oficina.entrypoint.webhook.AssinaturaWebhook;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@Tag(name = "Webhook", description = "Notificações do Mercado Pago")
public class WebhookMercadoPagoController {

    private final BillingUseCase billingUseCase;
    private final String segredo;

    public WebhookMercadoPagoController(BillingUseCase billingUseCase,
                                        @Value("${app.mercadopago.webhook-secret:}") String segredo) {
        this.billingUseCase = billingUseCase;
        this.segredo = segredo;
    }

    /**
     * A documentação se contradiz sobre a assinatura em notificações de QR. Assinatura presente é
     * sempre conferida; a confirmação de pagamento depende só da consulta à API, nunca deste corpo.
     */
    @PostMapping("/webhooks/mercado-pago")
    @Operation(summary = "Recebe notificações de order do Mercado Pago")
    public ResponseEntity<Void> receber(@RequestParam(name = "data.id", required = false) String dataId,
                                        @RequestParam(required = false) String type,
                                        @RequestHeader(name = "x-signature", required = false) String assinatura,
                                        @RequestHeader(name = "x-request-id", required = false) String requestId) {
        if (dataId == null || !"order".equals(type)) {
            log.info("Notificação ignorada: type={}, data.id={}", type, dataId);
            return ResponseEntity.ok().build();
        }
        if (assinatura != null && !segredo.isBlank() && !AssinaturaWebhook.valida(assinatura, dataId, requestId, segredo)) {
            log.warn("Assinatura inválida no webhook da order {}", dataId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        billingUseCase.processarNotificacaoOrder(dataId);
        return ResponseEntity.ok().build();
    }
}
