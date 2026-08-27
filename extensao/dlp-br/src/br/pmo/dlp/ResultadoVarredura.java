package br.pmo.dlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import br.pmo.dlp.RegrasSensiveis.Severidade;

/**
 * O que sobrou de uma varredura: os achados desduplicados, a classificacao
 * derivada e -- o campo que mais importa -- se a varredura foi COMPLETA.
 *
 * <p><b>{@link #isCompleta()} E' O CAMPO MAIS IMPORTANTE DESTA CLASSE.</b>
 * Um documento grande demais, ou que estourou o orcamento de tempo, sai daqui
 * com zero achado -- exatamente como um documento limpo. Se a politica olhar so'
 * para a lista de achados, arquivo grande vira o caminho trivial de exfiltracao:
 * basta empurrar o vazamento para o fim de um arquivo de 300 MB. Por isso
 * {@code completa=false} e' um estado de PRIMEIRA CLASSE, e
 * {@link PoliticaDlp} trata varredura incompleta como suspeita, nunca como
 * limpa.
 */
public final class ResultadoVarredura {

  private final List<Achado> achados;

  private final Classificacao classificacao;

  private final boolean completa;

  private final String motivoIncompleta;

  private final int caracteresVarridos;

  private final long milissegundos;

  ResultadoVarredura(List<Achado> achados,
                     boolean completa,
                     String motivoIncompleta,
                     int caracteresVarridos,
                     long milissegundos) {
    this.achados = Collections.unmodifiableList(new ArrayList<>(achados));
    this.classificacao = Classificacao.derivar(this.achados);
    this.completa = completa;
    this.motivoIncompleta = motivoIncompleta;
    this.caracteresVarridos = caracteresVarridos;
    this.milissegundos = milissegundos;
  }

  /** Achados por regra, ordenados por severidade decrescente e depois por rotulo. */
  public List<Achado> getAchados() {
    return achados;
  }

  /** Rotulo de sigilo derivado do conteudo. Ver {@link Classificacao}. */
  public Classificacao getClassificacao() {
    return classificacao;
  }

  /**
   * Falso quando o motor NAO conseguiu olhar o documento inteiro. Resultado
   * incompleto sem achado NAO significa documento limpo.
   */
  public boolean isCompleta() {
    return completa;
  }

  /** Em portugues, para ir direto ao relatorio. Nulo quando completa. */
  public String getMotivoIncompleta() {
    return motivoIncompleta;
  }

  public int getCaracteresVarridos() {
    return caracteresVarridos;
  }

  public long getMilissegundos() {
    return milissegundos;
  }

  public boolean isLimpo() {
    return achados.isEmpty();
  }

  /** A maior severidade encontrada, ou nulo se nada foi encontrado. */
  public Severidade getSeveridadeMaxima() {
    Severidade maior = null;
    for (Achado achado : achados) {
      if (maior == null || achado.getSeveridade().compareTo(maior) > 0) {
        maior = achado.getSeveridade();
      }
    }
    return maior;
  }

  /** Soma das ocorrencias de todos os achados. */
  public int getTotalOcorrencias() {
    int total = 0;
    for (Achado achado : achados) {
      total += achado.getQuantidade();
    }
    return total;
  }

  public Achado getAchado(String rotulo) {
    for (Achado achado : achados) {
      if (achado.getRotulo().equals(rotulo)) {
        return achado;
      }
    }
    return null;
  }

  /**
   * Linha unica para log e para a coluna "motivo" da quarentena. So' rotulo e
   * quantidade -- nenhum valor, nem mascarado: log e' o lugar de onde o dado
   * mais escapa, porque e' o que vai para fora sem ninguem reler.
   */
  public String resumo() {
    if (achados.isEmpty()) {
      return completa ? "nada encontrado" : "NAO VARRIDO: " + motivoIncompleta;
    }
    StringBuilder sb = new StringBuilder();
    sb.append(classificacao).append(": ");
    for (int i = 0; i < achados.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(achados.get(i).getRotulo()).append(" x").append(achados.get(i).getQuantidade());
    }
    if (!completa) {
      sb.append(" (varredura incompleta: ").append(motivoIncompleta).append(')');
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "ResultadoVarredura[" + resumo() + "]";
  }
}
