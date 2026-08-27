package br.pmo.dlp;

/**
 * Uma ocorrencia confirmada de dado sensivel: o trecho bruto e ONDE ele estava.
 *
 * <p><b>POR QUE GUARDAR A POSICAO, E NAO SO' O TEXTO.</b> Duas coisas dependem
 * disso e nenhuma funciona com o trecho solto:
 *
 * <ol>
 *   <li><b>Mascaramento.</b> Trocar o texto por substituicao cega
 *       ({@code texto.replace(bruto, mascara)}) erra sempre que o mesmo numero
 *       aparece duas vezes com significados diferentes, e erra feio quando o
 *       trecho e' curto o bastante para casar dentro de outra palavra. Com
 *       inicio e fim a substituicao e' cirurgica.</li>
 *   <li><b>Desduplicacao.</b> CPF e CNH tem os mesmos onze digitos, e um numero
 *       pode fechar nos dois algoritmos. Sem posicao, o relatorio diz
 *       "1 CPF e 1 CNH" para UM unico numero -- e a contagem de um relatorio de
 *       conformidade que dobra achados nao serve para nada. Com posicao, o
 *       {@link Varredura} sabe que e' o mesmo trecho e mantem so' o de maior
 *       severidade.</li>
 * </ol>
 *
 * <p>Imutavel de proposito: uma ocorrencia atravessa camadas (motor, politica,
 * relatorio, REST) e nenhuma delas tem motivo para altera-la.
 */
public final class Ocorrencia {

  private final String bruto;

  private final int inicio;

  private final int fim;

  public Ocorrencia(String bruto, int inicio, int fim) {
    if (bruto == null) {
      throw new IllegalArgumentException("trecho bruto nulo");
    }
    if (inicio < 0 || fim < inicio) {
      throw new IllegalArgumentException("intervalo invalido: " + inicio + ".." + fim);
    }
    this.bruto = bruto;
    this.inicio = inicio;
    this.fim = fim;
  }

  /** O texto exatamente como estava no documento, com pontuacao e tudo. */
  public String getBruto() {
    return bruto;
  }

  /** Indice do primeiro caractere, base 0, inclusivo. */
  public int getInicio() {
    return inicio;
  }

  /** Indice logo APOS o ultimo caractere, no padrao de {@code String.substring}. */
  public int getFim() {
    return fim;
  }

  public int getComprimento() {
    return fim - inicio;
  }

  /**
   * Verdadeiro quando os dois intervalos compartilham ao menos um caractere.
   * E' o teste que sustenta a desduplicacao de CPF/CNH e de telefone/CPF.
   */
  public boolean sobrepoe(Ocorrencia outra) {
    return outra != null && this.inicio < outra.fim && outra.inicio < this.fim;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Ocorrencia)) {
      return false;
    }
    Ocorrencia outra = (Ocorrencia) o;
    return inicio == outra.inicio && fim == outra.fim && bruto.equals(outra.bruto);
  }

  @Override
  public int hashCode() {
    return (inicio * 31 + fim) * 31 + bruto.hashCode();
  }

  /**
   * NUNCA imprime o valor bruto. Um {@code toString} de dado pessoal acaba em
   * log, em stack trace e em ticket de suporte -- ou seja, vaza exatamente o
   * que este pacote existe para conter.
   */
  @Override
  public String toString() {
    return "Ocorrencia[" + inicio + ".." + fim + ", " + getComprimento() + " chars]";
  }
}
