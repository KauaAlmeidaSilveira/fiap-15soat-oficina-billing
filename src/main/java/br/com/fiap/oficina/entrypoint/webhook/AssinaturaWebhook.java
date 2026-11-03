package br.com.fiap.oficina.entrypoint.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Formato do manifesto a conferir na compra de teste: as páginas lidas não o descrevem por completo. */
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
        String manifesto = "id:" + dataId.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:" + ts + ";";
        return MessageDigest.isEqual(hmac(manifesto, segredo).getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
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
