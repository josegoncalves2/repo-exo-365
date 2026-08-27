package br.pmo.dlp;

/**
 * Ponto de entrada das provas do nucleo do DLP.
 *
 * <p>Roda com {@code javac} e {@code java}, sem rede e sem dependencia externa.
 * Sai com codigo 0 se tudo passou e 1 se qualquer asseveracao falhou -- para o
 * build da imagem poder ABORTAR quando o motor regride, em vez de embarcar um
 * DLP quebrado que so' vai aparecer em producao.
 */
public final class Provas {

  private Provas() {
  }

  public static void main(String[] args) {
    System.out.println("===============================================================");
    System.out.println(" PROVAS DO NUCLEO DLP — br.pmo.dlp");
    System.out.println(" Nenhum dado abaixo pertence a pessoa real: todos os numeros");
    System.out.println(" foram gerados pelo algoritmo publico de digito verificador.");
    System.out.println("===============================================================");

    ProvaRegras.rodar();
    ProvaVarredura.rodar();
    ProvaMascarador.rodar();
    ProvaPolitica.rodar();
    ProvaExtrator.rodar();

    System.exit(Prova.fechar());
  }
}
