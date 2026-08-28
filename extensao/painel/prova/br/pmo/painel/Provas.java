package br.pmo.painel;

/**
 * Ponto de entrada das provas do nucleo do painel.
 *
 * <p>Roda com {@code javac} e {@code java}, sem rede e sem JUnit. Sai com codigo
 * 0 se tudo passou e 1 se qualquer asseveracao falhou, para o build PARAR antes
 * de empacotar. Uma tela de conformidade que regride em silencio e' pior que
 * tela nenhuma: ela produz numero, e numero errado num relatorio de LGPD e' pior
 * que numero nenhum, porque destroi a confianca em todos os outros numeros do
 * mesmo relatorio.
 *
 * <p><b>ISTO NAO E' ACEITE DE FUNCIONALIDADE.</b> Nenhuma asseveracao abaixo
 * prova que o no' de menu aparece no portal, que a pagina abre ou que o WAR
 * publica. Isso e' aceite humano, com mouse e teclado. O que se prova aqui e' o
 * que da' para provar sem portal: as contas, os rotulos e o escape.
 */
public final class Provas {

  private Provas() {
  }

  public static void main(String[] args) {
    System.out.println("===============================================================");
    System.out.println(" PROVAS DO NUCLEO DO PAINEL — br.pmo.painel");
    System.out.println(" Nenhum CPF abaixo pertence a pessoa real: todos foram gerados");
    System.out.println(" pelo algoritmo publico de digito verificador.");
    System.out.println("===============================================================");

    ProvaEscape.rodar();
    ProvaRotulos.rodar();
    ProvaTextoSubmetido.rodar();
    ProvaAnalise.rodar();
    ProvaPainelHtml.rodar();

    System.exit(Prova.fechar());
  }
}
