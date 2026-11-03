# Oficina — Billing Service

Microsserviço da Fase 4 do Tech Challenge: orçamento, aprovação, pagamento e integração com o Mercado Pago.

> Em construção. Nesta etapa contém a base técnica, o token de aprovação e o envio de e-mail, migrados do monólito da Fase 3. A documentação geral está em [`fiap-15soat-oficina-os`](https://github.com/KauaAlmeidaSilveira/fiap-15soat-oficina-os).

## Rodar os testes

Requer Java 21 e Maven 3.9+. As chaves JWT não são versionadas; gere em PKCS#8:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/jwt.private.key
openssl rsa -in src/main/resources/jwt.private.key -pubout -out src/main/resources/jwt.public.key
mvn verify
```
