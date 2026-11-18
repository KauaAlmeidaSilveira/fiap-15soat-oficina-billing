# Oficina — Billing Service

Microsserviço da Fase 4 do Tech Challenge: orçamento, aprovação, pagamento e integração com o Mercado Pago. Recebe comandos da Saga pelo SQS e responde com eventos. A estratégia geral está no [OS Service](https://github.com/KauaAlmeidaSilveira/fiap-15soat-oficina-os).

- **Banco:** DynamoDB em tabela única, com escritas condicionais que garantem que a primeira decisão vence e que um pagamento confirma uma vez só.
- **Mercado Pago:** QR dinâmico pela API de orders. O corpo do webhook nunca confirma pagamento: toda notificação consulta `GET /v1/orders/{id}`.
- **Link de aprovação:** par de chaves próprio, separado do token de sessão.
- **E-mails:** orçamento *best-effort*, QR com 3 tentativas e aviso de cancelamento.

> **Testado com conta real de teste do Mercado Pago em 15/11/2026, na AWS:** QR dinâmico (`qr_data`), pagamento aprovado pelo app e webhook assinado (o manifesto usa o id da order na grafia original). Cancelamento e pagamento recusado estão cobertos contra o simulador (WireMock).

## Modelo de dados

DynamoDB, tabela única `oficina-billing` (chave `pk` + `sk`). Orçamento e cobrança ficam sob a mesma partição da OS,
como documento JSON no atributo `dados`; `status` fica fora do documento para as escritas condicionais.

| Item | `pk` | `sk` | Atributos | Para quê |
|---|---|---|---|---|
| Orçamento | `OS#<osId>` | `ORCAMENTO` | `status`, `dados` | Itens, valor, cliente, veículo, prazo e decisão |
| Cobrança | `OS#<osId>` | `COBRANCA` | `status`, `dados` | Order do Mercado Pago, `qr_data`, valor, prazo e pagamento |
| Outbox | `OUTBOX#<uuid>` | `MSG` | `outboxPendente`, `criadoEm`, `fila`, `tipo`, `corpo`, `reservadoAte` | Evento gravado na mesma transação do estado |
| Inbox | `INBOX#<messageId>` | `MSG` | `expiraEm` (TTL de 14 dias) | Comando já processado |

```mermaid
erDiagram
    ORCAMENTO ||--o| COBRANCA : "mesma pk OS#osId"
    ORCAMENTO ||--|{ ITEM : "lista em dados"

    ORCAMENTO {
        string pk PK "OS#osId"
        string sk PK "ORCAMENTO"
        string status "ENVIADO, APROVADO, RECUSADO, EXPIRADO, CANCELADO"
        uuid id
        uuid sagaId
        string numeroOs
        decimal valorTotal
        instant expiraEm "7 dias"
        string clienteNome
        string clienteEmail
        string veiculoPlaca
        string origemDecisao "CLIENTE ou OFICINA"
        instant decididoEm
    }
    ITEM {
        string nomeProduto
        int quantidade
        decimal precoUnitario
    }
    COBRANCA {
        string pk PK "OS#osId"
        string sk PK "COBRANCA"
        string status "CRIADA, PAGA, ENCERRADA, ESTORNADA, BLOQUEADA"
        uuid id
        uuid orcamentoId
        decimal valor
        string mpOrderId "order do Mercado Pago"
        string paymentId
        string qrData
        instant expiraEm "validade do QR"
        instant pagoEm
    }
    OUTBOX {
        string pk PK "OUTBOX#uuid"
        string sk PK "MSG"
        string outboxPendente "GSI, removido ao publicar"
        string criadoEm "GSI"
        string fila
        string tipo
        string corpo "envelope JSON"
        number reservadoAte "reserva de 30 s"
    }
    INBOX {
        string pk PK "INBOX#messageId"
        string sk PK "MSG"
        number expiraEm "TTL"
    }
```

- **Escritas condicionais:** a decisão do orçamento só sai de `ENVIADO` (a primeira vence; a segunda vira 409), e o
  pagamento só confirma uma cobrança `CRIADA` ou `ENCERRADA` (pagamento tardio, que a Saga reembolsa), sempre condicionado
  ao status lido. Uma cobrança já `PAGA` não muda: webhook repetido não paga duas vezes.
- **Transação:** a mudança de status, o evento no outbox e o registro no inbox vão juntos em `TransactWriteItems`.
- **Índice `outbox-pendente`:** GSI esparso (`outboxPendente` + `criadoEm`); só os itens ainda não publicados aparecem
  nele. Cada réplica reserva o item por 30 s antes de publicar.

## Rodar os testes

Requer Java 21, Maven 3.9+ e Docker para o Testcontainers:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl rsa -pubout -out src/main/resources/jwt.public.key
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/aprovacao.private.key
openssl rsa -in src/main/resources/aprovacao.private.key -pubout -out src/main/resources/aprovacao.public.key
mvn verify
```

## Cobertura e qualidade

O `mvn verify` falha se a cobertura de instruções do serviço inteiro ficar abaixo de 80% (JaCoCo, só
`DataLoader` e `OficinaApplication` fora da conta). O relatório fica em `target/site/jacoco/index.html`.

Execução local de 16/11/2026, na `main` (`7aef492`):

| Testes | Falhas | Instruções | Linhas | Ramos | Gate (80% de instruções) |
|---|---|---|---|---|---|
| 93 | 0 | 94,9% | 94,3% | 84,3% | Passou |

No SonarQube local de 17/11/2026 (`scripts/sonar-local.sh` no OS), o Billing passou no quality gate "Sonar way"
com 92,5% de cobertura e nota A em confiabilidade, segurança, revisão de segurança e manutenibilidade. Os três
serviços estão na seção "Qualidade" do [README do OS](https://github.com/KauaAlmeidaSilveira/fiap-15soat-oficina-os#qualidade-sonarqube).
O `ci.yml` roda o mesmo `mvn verify` no GitHub Actions a cada PR e push na `main` (verde desde 17/11/2026); o
passo do SonarCloud fica pulado sem o secret `SONAR_TOKEN`.

![SonarQube: Billing aprovado no quality gate](https://raw.githubusercontent.com/KauaAlmeidaSilveira/fiap-15soat-oficina-os/main/docs/evidencias/sonar/billing.png)
