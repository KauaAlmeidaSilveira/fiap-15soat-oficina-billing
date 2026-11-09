# Oficina — Billing Service

Microsserviço da Fase 4 do Tech Challenge: orçamento, aprovação, pagamento e integração com o Mercado Pago. Recebe comandos da Saga pelo SQS e responde com eventos. A estratégia geral está no [OS Service](https://github.com/KauaAlmeidaSilveira/fiap-15soat-oficina-os).

- **Banco:** DynamoDB em tabela única, com escritas condicionais que garantem que a primeira decisão vence e que um pagamento confirma uma vez só.
- **Mercado Pago:** QR dinâmico pela API de orders. O corpo do webhook nunca confirma pagamento: toda notificação consulta `GET /v1/orders/{id}`.
- **Link de aprovação:** par de chaves próprio, separado do token de sessão.
- **E-mails:** orçamento *best-effort*, QR com 3 tentativas e aviso de cancelamento.

> **Ainda não testado com conta real do Mercado Pago.** O caminho do `qr_data`, o método de cancelamento, o formato da assinatura e o status de pagamento recusado seguem a documentação e precisam ser conferidos na compra de teste.

## Rodar os testes

Requer Java 21, Maven 3.9+ e Docker para o Testcontainers:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl rsa -pubout -out src/main/resources/jwt.public.key
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/aprovacao.private.key
openssl rsa -in src/main/resources/aprovacao.private.key -pubout -out src/main/resources/aprovacao.public.key
mvn verify
```
