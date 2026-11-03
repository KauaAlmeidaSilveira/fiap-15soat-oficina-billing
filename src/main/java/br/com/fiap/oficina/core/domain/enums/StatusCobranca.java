package br.com.fiap.oficina.core.domain.enums;

public enum StatusCobranca {
    /** Order criada no Mercado Pago, aguardando pagamento. */
    CRIADA,
    PAGA,
    ENCERRADA,
    ESTORNADA,
    /** Marcador: encerrada antes de existir, para recusar um GerarCobranca atrasado. */
    BLOQUEADA
}
