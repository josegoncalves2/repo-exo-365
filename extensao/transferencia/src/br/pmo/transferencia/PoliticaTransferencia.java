package br.pmo.transferencia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A politica: uma lista ORDENADA de regras, primeira que casa decide.
 *
 * <h2>Primeira que casa, e por que nao "a mais restritiva vence"</h2>
 *
 * "A mais restritiva vence" parece mais segura e e' pior: torna impossivel
 * escrever uma excecao. "Ninguem baixa .pst, MENOS o grupo TI" precisa que a
 * permissao especifica seja avaliada antes da proibicao ampla. Com precedencia
 * por restricao, a excecao nunca se aplica e o administrador conclui que a
 * ferramenta esta' quebrada ; ou, pior, desliga a proibicao inteira para
 * destravar uma pessoa.
 *
 * <p>Primeira-que-casa e' previsivel e auditavel: dado um pedido, existe UMA
 * regra responsavel pela decisao, e ela tem nome. E' isso que se responde
 * quando um servidor pergunta "por que nao consigo baixar este arquivo".
 *
 * <h2>Acao padrao</h2>
 *
 * Nenhuma regra casou: decide a acao padrao. Ela nasce PERMITIR, e isso e'
 * deliberado. Uma politica de transferencia que nasce NEGAR, aplicada a um
 * portal em producao, para o trabalho de todo mundo no primeiro minuto ; e a
 * reacao previsivel da administracao e' desligar o recurso inteiro, ficando
 * com menos controle do que antes. Fecha-se depois, com as regras escritas e
 * medidas.
 *
 * <h2>Modo observacao</h2>
 *
 * Em observacao, a politica AVALIA e REGISTRA, mas nao impede. E' o unico modo
 * honesto de descobrir, em trafego real, quais rotas e quais pedidos existem
 * de fato antes de bloquear qualquer coisa ; bloquear com base em suposicao
 * sobre as rotas produz tanto liberacao indevida quanto interrupcao indevida,
 * e as duas passam despercebidas.
 */
public final class PoliticaTransferencia {

  /** Como a politica se comporta ao decidir NEGAR. */
  public enum Modo {
    /** Avalia, registra e NAO impede. Para levantamento. */
    OBSERVACAO,
    /** Avalia, registra e impede. */
    APLICACAO;

    public static Modo de(String texto, Modo padrao) {
      if (texto == null || texto.trim().isEmpty()) {
        return padrao;
      }
      try {
        return valueOf(texto.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // Configuracao errada nao pode virar bloqueio geral silencioso.
        return padrao;
      }
    }
  }

  /** A decisao, com a regra responsavel nomeada. */
  public static final class Decisao {
    private final boolean permitido;
    private final boolean impedeDeFato;
    private final String regra;
    private final String motivo;

    Decisao(boolean permitido, boolean impedeDeFato, String regra, String motivo) {
      this.permitido = permitido;
      this.impedeDeFato = impedeDeFato;
      this.regra = regra;
      this.motivo = motivo;
    }

    /** A politica considera o pedido permitido? */
    public boolean isPermitido() {
      return permitido;
    }

    /**
     * O pedido deve ser efetivamente impedido AGORA?
     *
     * <p>Difere de {@link #isPermitido()} em modo OBSERVACAO: la', um pedido
     * negado continua sendo atendido, e so' o registro acusa. Manter os dois
     * separados evita o erro de ler "negado" e bloquear em modo de
     * levantamento.
     */
    public boolean impedeDeFato() {
      return impedeDeFato;
    }

    public String getRegra() {
      return regra;
    }

    public String getMotivo() {
      return motivo;
    }

    @Override
    public String toString() {
      return (permitido ? "PERMITIDO" : "NEGADO")
             + (impedeDeFato ? " (impedido)" : " (observacao)")
             + " por " + regra + ": " + motivo;
    }
  }

  private final List<Regra> regras;
  private final Regra.Efeito acaoPadrao;
  private final Modo modo;

  public PoliticaTransferencia(List<Regra> regras, Regra.Efeito acaoPadrao, Modo modo) {
    List<Regra> copia = new ArrayList<>();
    if (regras != null) {
      for (Regra regra : regras) {
        if (regra != null) {
          copia.add(regra);
        }
      }
    }
    this.regras = Collections.unmodifiableList(copia);
    this.acaoPadrao = acaoPadrao == null ? Regra.Efeito.PERMITIR : acaoPadrao;
    this.modo = modo == null ? Modo.OBSERVACAO : modo;
  }

  public List<Regra> getRegras() {
    return regras;
  }

  public Regra.Efeito getAcaoPadrao() {
    return acaoPadrao;
  }

  public Modo getModo() {
    return modo;
  }

  /** Sem regra nenhuma e com padrao PERMITIR, a politica nao opina. */
  public boolean estaInerte() {
    return regras.isEmpty() && acaoPadrao == Regra.Efeito.PERMITIR;
  }

  public Decisao decidir(Pedido pedido) {
    for (Regra regra : regras) {
      if (regra.casa(pedido)) {
        boolean permitido = regra.getEfeito() == Regra.Efeito.PERMITIR;
        return new Decisao(permitido,
                           !permitido && modo == Modo.APLICACAO,
                           regra.getNome(),
                           regra.getMotivo());
      }
    }
    boolean permitido = acaoPadrao == Regra.Efeito.PERMITIR;
    return new Decisao(permitido,
                       !permitido && modo == Modo.APLICACAO,
                       "(acao padrao)",
                       "nenhuma regra casou; acao padrao e' " + acaoPadrao);
  }
}
