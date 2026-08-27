package br.pmo.dlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import br.pmo.dlp.RegrasSensiveis.Severidade;

/**
 * Tudo o que UMA regra encontrou num documento: o rotulo, a severidade e cada
 * ocorrencia com sua posicao.
 *
 * <p><b>POR QUE UM ACHADO AGREGA A REGRA INTEIRA, E NAO E' UM POR OCORRENCIA.</b>
 * Um contracheque tem trezentos CPFs. Trezentos achados separados enchem o
 * banco, enchem a tela do administrador e nao dizem nada que "CPF: 300
 * ocorrencias" ja' nao diga melhor. A decisao de politica tambem e' por tipo,
 * nunca por ocorrencia: ninguem escreve regra para o 147o CPF do arquivo.
 *
 * <p><b>AMOSTRA MASCARADA.</b> O achado carrega ate {@link #MAX_AMOSTRAS}
 * exemplos ja' mascarados, para o administrador conferir na tela que a deteccao
 * faz sentido -- sem isso ele so' ve "CPF: 300" e nao tem como saber se a regra
 * acertou ou se casou com numero de protocolo. As amostras nunca saem em claro:
 * a tela de quem investiga vazamento nao pode ser, ela propria, um vazamento.
 *
 * <p>Imutavel.
 */
public final class Achado {

  /**
   * Quantas amostras acompanham o achado. Tres: o bastante para reconhecer um
   * padrao de falso positivo (tres numeros de protocolo seguidos saltam aos
   * olhos) e pouco o bastante para caber numa linha de tabela.
   */
  public static final int MAX_AMOSTRAS = 3;

  private final String rotulo;

  private final Severidade severidade;

  private final List<Ocorrencia> ocorrencias;

  private final List<String> amostrasMascaradas;

  public Achado(String rotulo, Severidade severidade, List<Ocorrencia> ocorrencias) {
    if (rotulo == null || severidade == null) {
      throw new IllegalArgumentException("achado exige rotulo e severidade");
    }
    if (ocorrencias == null || ocorrencias.isEmpty()) {
      throw new IllegalArgumentException("achado sem ocorrencia nao e' achado");
    }
    this.rotulo = rotulo;
    this.severidade = severidade;
    this.ocorrencias = Collections.unmodifiableList(new ArrayList<>(ocorrencias));

    List<String> amostras = new ArrayList<>(MAX_AMOSTRAS);
    for (int i = 0; i < this.ocorrencias.size() && i < MAX_AMOSTRAS; i++) {
      amostras.add(Mascarador.mascarar(rotulo, this.ocorrencias.get(i).getBruto()));
    }
    this.amostrasMascaradas = Collections.unmodifiableList(amostras);
  }

  public String getRotulo() {
    return rotulo;
  }

  public Severidade getSeveridade() {
    return severidade;
  }

  /** Todas as ocorrencias, na ordem em que aparecem no documento. */
  public List<Ocorrencia> getOcorrencias() {
    return ocorrencias;
  }

  public int getQuantidade() {
    return ocorrencias.size();
  }

  /** Exemplos ja' mascarados, seguros para tela, log e relatorio. */
  public List<String> getAmostrasMascaradas() {
    return amostrasMascaradas;
  }

  /** Nunca imprime valor bruto. Ver {@link Ocorrencia#toString()}. */
  @Override
  public String toString() {
    return rotulo + "[" + severidade + ", " + getQuantidade() + "x]";
  }
}
