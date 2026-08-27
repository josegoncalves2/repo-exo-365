package br.pmo.nuvem;

/**
 * Ponto de entrada das provas do nucleo do conector de nuvem.
 *
 * <p>Roda com {@code javac} e {@code java}, sem rede externa (os servidores de
 * teste sao locais) e sem dependencia fora do JDK. Sai com codigo 0 se tudo
 * passou e 1 se qualquer asseveracao falhou -- para o build da imagem poder
 * ABORTAR quando o nucleo regride, em vez de embarcar um conector quebrado.
 */
public final class Provas {

  private Provas() {
  }

  public static void main(String[] args) throws Exception {
    System.out.println("===============================================================");
    System.out.println(" PROVAS DO NUCLEO DE NUVEM — br.pmo.nuvem");
    System.out.println("===============================================================");

    ProvaCaminho.rodar();
    ProvaConflito.rodar();
    ProvaOAuth2.rodar();
    ProvaWebDav.rodar();

    System.exit(Prova.fechar());
  }
}
