package br.pmo.dlp;

import java.util.ArrayList;
import java.util.List;

/**
 * Micro-arcabouco de asseveracao, escrito a mao.
 *
 * <p><b>POR QUE NAO JUNIT.</b> O nucleo do DLP nao depende de nada fora do JDK,
 * e isso e' um bem que se perde na primeira dependencia adicionada: passar a
 * exigir JUnit no classpath significaria baixar jar para provar uma conta de
 * digito verificador, e num servidor sem saida para a internet a prova
 * simplesmente nao roda. Sessenta linhas de {@code if} resolvem, e a prova
 * roda com {@code javac} e {@code java}, mais nada.
 *
 * <p>Cada asseveracao registra o que verificou -- inclusive as que passaram --
 * porque a saida desta classe e' evidencia de auditoria, e evidencia que so'
 * mostra falha nao prova que o resto foi olhado.
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
