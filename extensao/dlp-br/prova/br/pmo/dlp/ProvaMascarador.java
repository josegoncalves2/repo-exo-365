package br.pmo.dlp;

/**
 * Prova do mascaramento. Duas perguntas, e a segunda e' a que interessa:
 * <ol>
 *   <li>o formato sobreviveu?</li>
 *   <li><b>o dado sumiu mesmo?</b> -- ou seja, o texto mascarado, varrido de
 *       novo pelo motor, nao pode devolver achado nenhum. Mascara que continua
 *       casando com a regra e' mascara que nao mascarou.</li>
 * </ol>
 */
final class ProvaMascarador {

  private ProvaMascarador() {
  }

  static void rodar() {
    formatoPreservado();
    naoSobraDado();
    substituicaoNaoDesalinha();
  }

  private static void formatoPreservado() {
    Prova.secao("Mascara — preserva o formato e revela so' o combinado");

    Prova.igual("CPF pontuado", "***.***.777-35",
                Mascarador.mascarar("CPF", ProvaRegras.CPF_VALIDO_1));
    Prova.igual("CPF sem pontuacao revela os MESMOS digitos", "********77735",
                Mascarador.mascarar("CPF", "11144477735"));
    Prova.igual("CNPJ revela a ordem do estabelecimento", "**.***.***/0001-**",
                Mascarador.mascarar("CNPJ", ProvaRegras.CNPJ_VALIDO_1));
    Prova.igual("cartao revela so' os 4 ultimos", "**** **** **** 1486",
                Mascarador.mascarar("CARTAO_CREDITO", ProvaRegras.CARTAO_VALIDO));
    Prova.igual("e-mail mantem o dominio", "s**************@pmo.gov.br",
                Mascarador.mascarar("EMAIL", "setor.protocolo@pmo.gov.br"));
    Prova.igual("telefone mantem o DDD e os 2 ultimos", "(11) ****-**55",
                Mascarador.mascarar("TELEFONE", "(11) 3221-4455"));
    Prova.igual("CEP revela o prefixo do municipio", "01310-***",
                Mascarador.mascarar("CEP", "01310-100"));
    Prova.igual("senha nao revela NADA do valor", "senha: ***",
                Mascarador.mascarar("SEGREDO_EM_TEXTO_CLARO", "senha: correiacavalobateria"));
    Prova.igual("nem o comprimento da senha vaza", "senha: ***",
                Mascarador.mascarar("SEGREDO_EM_TEXTO_CLARO", "senha: 1234567890123456789012345"));
    Prova.igual("rotulo desconhecido cai na regra generica (2 ultimos)", "*******35",
                Mascarador.mascarar("ROTULO_QUE_NAO_EXISTE", "111444735"));
  }

  /** A prova de verdade: varrer o texto mascarado nao pode achar nada. */
  private static void naoSobraDado() {
    Prova.secao("Mascara — o texto mascarado, varrido de novo, nao acha mais nada");

    String original = "Requerente CPF " + ProvaRegras.CPF_VALIDO_1
        + ", empresa CNPJ " + ProvaRegras.CNPJ_VALIDO_1
        + ", cartao " + ProvaRegras.CARTAO_VALIDO
        + ", CNH " + ProvaRegras.CNH_VALIDA
        + ", PIS " + ProvaRegras.PIS_VALIDO
        + ", titulo " + ProvaRegras.TITULO_VALIDO
        + ", contato joao.silva@pmo.gov.br telefone (11) 3221-4455.";

    Varredura motor = new Varredura();
    ResultadoVarredura antes = motor.varrer(original);
    System.out.println("   ..   antes:  " + antes.resumo());
    Prova.certo("o texto original tem achados de severidade ALTA",
                antes.getSeveridadeMaxima() == RegrasSensiveis.Severidade.ALTA);

    String mascarado = Mascarador.mascararTexto(original, antes.getAchados());
    System.out.println("   ..   texto mascarado: " + mascarado);

    ResultadoVarredura depois = motor.varrer(mascarado);
    System.out.println("   ..   depois: " + depois.resumo());

    Prova.certo("nenhum achado de severidade ALTA sobra depois de mascarar",
                depois.getSeveridadeMaxima() != RegrasSensiveis.Severidade.ALTA);
    Prova.certo("CPF sumiu", depois.getAchado("CPF") == null);
    Prova.certo("CNPJ sumiu", depois.getAchado("CNPJ") == null);
    Prova.certo("cartao sumiu", depois.getAchado("CARTAO_CREDITO") == null);
    Prova.certo("CNH sumiu", depois.getAchado("CNH") == null);
    Prova.certo("PIS sumiu", depois.getAchado("PIS_PASEP") == null);
    Prova.certo("titulo de eleitor sumiu", depois.getAchado("TITULO_ELEITOR") == null);
  }

  /**
   * Mascarar de tras para frente e' o que permite trocar trechos de comprimento
   * diferente sem desalinhar os indices seguintes. Aqui isso e' verificado com
   * o unico rotulo cuja mascara MUDA de comprimento: o segredo em texto claro.
   */
  private static void substituicaoNaoDesalinha() {
    Prova.secao("Mascara — troca de comprimento diferente nao desalinha o resto");

    String texto = "config: senha=umaSenhaBemLongaDeVerdade; responsavel CPF "
                   + ProvaRegras.CPF_VALIDO_1 + "; fim.";
    Varredura motor = new Varredura();
    ResultadoVarredura r = motor.varrer(texto);
    String mascarado = Mascarador.mascararTexto(texto, r.getAchados());
    System.out.println("   ..   " + mascarado);

    Prova.certo("a senha foi mascarada", mascarado.contains("senha= ***"));
    Prova.certo("o CPF depois dela tambem foi", mascarado.contains("***.***.777-35"));
    Prova.certo("o texto ao redor ficou intacto",
                mascarado.startsWith("config: ") && mascarado.endsWith("; fim."));
    Prova.certo("e nada da senha original sobrou",
                !mascarado.contains("umaSenhaBemLongaDeVerdade"));
  }
}
