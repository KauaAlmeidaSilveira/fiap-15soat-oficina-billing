package br.com.fiap.oficina.dataprovider.notificacao;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class QrCodePng {

    private static final int TAMANHO = 280;

    private QrCodePng() {}

    static byte[] gerar(String conteudo) {
        try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(
                    new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, TAMANHO, TAMANHO), "PNG", saida);
            return saida.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalArgumentException("Não foi possível gerar o QR Code", e);
        }
    }
}
