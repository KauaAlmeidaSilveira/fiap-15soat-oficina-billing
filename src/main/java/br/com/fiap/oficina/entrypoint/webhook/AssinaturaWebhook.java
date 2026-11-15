package br.com.fiap.oficina.entrypoint.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Manifesto assinado: {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}, sem as partes ausentes.
 * Na notificação de order real (teste de 15/11/2026), o Mercado Pago assinou o data.id na grafia
 * original (ORDTST...), não em minúsculas como diz a documentação de payments; as duas são aceitas.
 */
public final class AssinaturaWebhook {

    private AssinaturaWebhook() {}

    public static boolean valida(String cabecalho, String dataId, String requestId, String segredo) {
        String ts = null;
        String v1 = null;
        for (String parte : cabecalho.split(",")) {
            String[] chaveValor = parte.trim().split("=", 2);
            if (chaveValor.length == 2 && "ts".equals(chaveValor[0])) {
                ts = chaveValor[1];
            } else if (chaveValor.length == 2 && "v1".equals(chaveValor[0])) {
                v1 = chaveValor[1];
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }
        byte[] recebida = v1.getBytes(StandardCharsets.UTF_8);
        for (String id : new String[] {dataId, dataId.toLowerCase(Locale.ROOT)}) {
            if (MessageDigest.isEqual(hmac(manifesto(id, requestId, ts), segredo).getBytes(StandardCharsets.UTF_8), recebida)) {
                return true;
            }
        }
        return false;
    }

    static String manifesto(String dataId, String requestId, String ts) {
        return "id:" + dataId + ";" + (requestId == null || requestId.isBlank() ? "" : "request-id:" + requestId + ";") + "ts:" + ts + ";";
    }

    public static String hmac(String manifesto, String segredo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(manifesto.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC indisponível", e);
        }
    }
}
