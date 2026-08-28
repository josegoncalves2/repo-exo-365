package br.pmo.painel;

import java.util.ArrayList;
import java.util.List;

/**
 * Micro-arcabouco de asseveracao, espelhado do que o nucleo do DLP ja' usa.
 *
 * <p><b>POR QUE UMA COPIA E NAO O DE {@code dlp-br}.</b> Porque o arcabouco de
 * la' e' codigo de outra entrega, e depender dele significaria que mexer nas
 * provas do DLP quebra o build do painel -- acoplamento entre dois artefatos que
 * nao tem nenhuma razao de negocio para andarem juntos. Sao sessenta linhas de
 * {@code if}; copiar sai mais barato que acoplar, e mantem a promessa de que o
 * painel se prova com {@code javac} e {@code java} e mais nada.
 *
 * <p>Registra tambem o que PASSOU: a saida e' evidencia de auditoria, e
 * evidencia que so' mostra falha nao prova que o resto foi olhado.
 */
public final class Prova {

  private static final List<String> FALHAS = new ArrayList<>();

  private static int total = 0;

  private static String secaoAtual = "(sem secao)";

  private Prova() {
  }

  public static void secao(String nome) {
    secaoAtual = nome;
    System.out.println();
    System.out.println("== " + nome);
  }

  public static void certo(String oQue, boolean condicao) {
    total++;
    if (condicao) {
      System.out.println("   ok   " + oQue);
    } else {
      System.out.println("   FALHA " + oQue);
      FALHAS.add(secaoAtual + " :: " + oQue);
    }
  }

  public static void igual(String oQue, Object esperado, Object obtido) {
    boolean bate = esperado == null ? obtido == null : esperado.equals(obtido);
    total++;
    if (bate) {
      System.out.println("   ok   " + oQue);
    } else {
      System.out.println("   FALHA " + oQue);
      System.out.println("          esperado: " + esperado);
      System.out.println("          obtido:   " + obtido);
      FALHAS.add(secaoAtual + " :: " + oQue + " (esperado " + esperado + ", obtido " + obtido + ")");
    }
  }

  /**
   * Asseveracao de que uma chamada REJEITA a entrada.
   *
   * <p>Existe porque "nao explodiu" nao e' prova de nada quando o contrato e'
   * justamente explodir. Sem isto, uma prova de validacao passa por acidente no
   * dia em que a validacao for removida.
   */
  public static void recusa(String oQue, Runnable acao) {
    total++;
    try {
      acao.run();
      System.out.println("   FALHA " + oQue + " (aceitou, deveria recusar)");
      FALHAS.add(secaoAtual + " :: " + oQue + " (aceitou, deveria recusar)");
    } catch (RuntimeException e) {
      System.out.println("   ok   " + oQue + " [" + e.getClass().getSimpleName() + "]");
    }
  }

  public static int getTotal() {
    return total;
  }

  public static List<String> getFalhas() {
    return FALHAS;
  }

  /** Imprime o fechamento e devolve o codigo de saida do processo. */
  public static int fechar() {
    System.out.println();
    System.out.println("---------------------------------------------------------------");
    if (FALHAS.isEmpty()) {
      System.out.println("RESULTADO: " + total + " asseveracoes, 0 falhas.");
      return 0;
    }
    System.out.println("RESULTADO: " + total + " asseveracoes, " + FALHAS.size() + " FALHAS:");
    for (String falha : FALHAS) {
      System.out.println("  - " + falha);
    }
    return 1;
  }
}
