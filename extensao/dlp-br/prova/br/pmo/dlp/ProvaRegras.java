package br.pmo.dlp;

import java.util.Map;

/**
 * Prova do catalogo de regras: os validadores aritmeticos e o que eles RECUSAM.
 *
 * <p>A parte que importa e' a segunda. Provar que o validador aceita um CPF
 * valido e' quase tautologia -- o numero foi construido com o mesmo algoritmo.
 * O que decide se este DLP serve em producao e' quanto ele RECUSA: numero de
 * protocolo, matricula, codigo de barras e sequencia repetida sao o volume real
 * de um acervo administrativo, e cada um deles aceito por engano e' um documento
 * legitimo tirado do ar.
 *
 * <p>Os numeros usados aqui sao SINTETICOS, gerados pelo algoritmo publico do
 * documento. Nenhum pertence a pessoa real.
 */
final class ProvaRegras {

  // Validos, com digito verificador conferido fora deste codigo.
  static final String CPF_VALIDO_1 = "111.444.777-35";
  static final String CPF_VALIDO_2 = "529.982.247-25";
  static final String CNPJ_VALIDO_1 = "11.222.333/0001-81";
  static final String CNPJ_VALIDO_2 = "19.098.765/0001-34";
  static final String PIS_VALIDO = "120.79046.40-5";
  static final String TITULO_VALIDO = "001020300175";
  static final String CNH_VALIDA = "12345678900";
  static final String CARTAO_VALIDO = "4539 5787 6362 1486";

  /** Onze digitos que NAO fecham em CPF nem em CNH -- o numero de protocolo. */
  static final String PROTOCOLO = "52601815908";

  /** Onze digitos que fecham nos DOIS algoritmos ao mesmo tempo. */
  static final String CPF_E_CNH = "36084852955";

  private ProvaRegras() {
  }

  static void rodar() {
    aceitaOqueEValido();
    recusaOqueNaoE();
    recusaDigitoTrocado();
    severidadeDeclarada();
  }

  private static void aceitaOqueEValido() {
    Prova.secao("Regras — aceita documento com digito verificador correto");

    Prova.certo("CPF 111.444.777-35 e' valido", RegrasSensiveis.cpfValido(CPF_VALIDO_1));
    Prova.certo("CPF 529.982.247-25 e' valido", RegrasSensiveis.cpfValido(CPF_VALIDO_2));
    Prova.certo("CPF sem pontuacao e' o mesmo CPF",
                RegrasSensiveis.cpfValido("11144477735"));
    Prova.certo("CNPJ 11.222.333/0001-81 e' valido", RegrasSensiveis.cnpjValido(CNPJ_VALIDO_1));
    Prova.certo("CNPJ 19.098.765/0001-34 e' valido", RegrasSensiveis.cnpjValido(CNPJ_VALIDO_2));
    Prova.certo("PIS 120.79046.40-5 e' valido", RegrasSensiveis.pisValido(PIS_VALIDO));
    Prova.certo("Titulo de eleitor 001020300175 e' valido",
                RegrasSensiveis.tituloEleitorValido(TITULO_VALIDO));
    Prova.certo("CNH 12345678900 e' valida", RegrasSensiveis.cnhValida(CNH_VALIDA));
    Prova.certo("Cartao 4539 5787 6362 1486 passa no Luhn",
                RegrasSensiveis.luhnValido(CARTAO_VALIDO));
    Prova.certo("Cartao Mastercard de teste passa no Luhn",
                RegrasSensiveis.luhnValido("5500005555555559"));
  }

  private static void recusaOqueNaoE() {
    Prova.secao("Regras — RECUSA o que so' parece documento (falso positivo)");

    Prova.certo("11 digitos repetidos (11111111111) NAO e' CPF",
                !RegrasSensiveis.cpfValido("11111111111"));
    Prova.certo("00000000000 NAO e' CPF", !RegrasSensiveis.cpfValido("00000000000"));
    Prova.certo("14 digitos repetidos NAO e' CNPJ",
                !RegrasSensiveis.cnpjValido("11111111111111"));
    Prova.certo("Numero de protocolo " + PROTOCOLO + " NAO e' CPF",
                !RegrasSensiveis.cpfValido(PROTOCOLO));
    Prova.certo("Numero de protocolo " + PROTOCOLO + " NAO e' CNH",
                !RegrasSensiveis.cnhValida(PROTOCOLO));
    Prova.certo("Sequencia 12345678901 NAO e' CPF",
                !RegrasSensiveis.cpfValido("12345678901"));
    Prova.certo("Cartao 4111111111111112 (digito trocado) reprova no Luhn",
                !RegrasSensiveis.luhnValido("4111111111111112"));
    Prova.certo("Titulo com UF 99 (inexistente) e' recusado",
                !RegrasSensiveis.tituloEleitorValido("001020309975"));
    Prova.certo("CPF com 10 digitos e' recusado", !RegrasSensiveis.cpfValido("1114447773"));
    Prova.certo("CNPJ com 13 digitos e' recusado", !RegrasSensiveis.cnpjValido("1122233300018"));

    Prova.secao("Regras — o texto administrativo comum nao gera achado");
    Map<String, Integer> nada = RegrasSensiveis.detectar(
        "Processo 52601815908 - Memorando 004/2026. Valor empenhado R$ 1.234.567,89. "
        + "Referencia da nota 0000000000000. Codigo de barras 84670000001234567890123456789012345678901234");
    Prova.certo("Protocolo, valor em reais e codigo de barras nao viram CPF/CNPJ",
                !nada.containsKey("CPF") && !nada.containsKey("CNPJ"));
  }

  /**
   * A prova mais forte do conjunto: para CADA documento valido, trocar o ULTIMO
   * digito tem de derrubar a validacao. Um validador que so' confere formato
   * passa nos testes de aceitacao e falha aqui.
   */
  private static void recusaDigitoTrocado() {
    Prova.secao("Regras — trocar UM digito derruba a validacao (prova do modulo 11)");

    Prova.certo("CPF com ultimo digito trocado e' recusado",
                todasAsTrocasFalham(CPF_VALIDO_1, RegrasSensiveis::cpfValido));
    Prova.certo("CNPJ com ultimo digito trocado e' recusado",
                todasAsTrocasFalham(CNPJ_VALIDO_1, RegrasSensiveis::cnpjValido));
    Prova.certo("PIS com ultimo digito trocado e' recusado",
                todasAsTrocasFalham(PIS_VALIDO, RegrasSensiveis::pisValido));
    Prova.certo("Titulo com ultimo digito trocado e' recusado",
                todasAsTrocasFalham(TITULO_VALIDO, RegrasSensiveis::tituloEleitorValido));
    Prova.certo("CNH com ultimo digito trocado e' recusada",
                todasAsTrocasFalham(CNH_VALIDA, RegrasSensiveis::cnhValida));
    Prova.certo("Cartao com ultimo digito trocado reprova no Luhn",
                todasAsTrocasFalham(CARTAO_VALIDO, RegrasSensiveis::luhnValido));
  }

  /** Troca o ultimo digito pelos outros nove e exige recusa em todos. */
  private static boolean todasAsTrocasFalham(String valido, java.util.function.Predicate<String> validador) {
    int ultimo = -1;
    for (int i = valido.length() - 1; i >= 0; i--) {
      if (Character.isDigit(valido.charAt(i))) {
        ultimo = i;
        break;
      }
    }
    if (ultimo < 0) {
      return false;
    }
    char original = valido.charAt(ultimo);
    for (char c = '0'; c <= '9'; c++) {
      if (c == original) {
        continue;
      }
      String adulterado = valido.substring(0, ultimo) + c + valido.substring(ultimo + 1);
      if (validador.test(adulterado)) {
        System.out.println("          aceitou indevidamente: " + adulterado);
        return false;
      }
    }
    return true;
  }

  private static void severidadeDeclarada() {
    Prova.secao("Regras — severidade de cada rotulo");
    for (RegrasSensiveis.Regra regra : RegrasSensiveis.regras()) {
      System.out.println("   ..   " + regra.getRotulo() + " = " + regra.getSeveridade());
    }
    Prova.igual("O catalogo tem 11 regras", 11, RegrasSensiveis.regras().size());
    Prova.certo("E-mail e' BAIXA (esta' no rodape de todo oficio)",
                severidadeDe("EMAIL") == RegrasSensiveis.Severidade.BAIXA);
    Prova.certo("CPF e' ALTA", severidadeDe("CPF") == RegrasSensiveis.Severidade.ALTA);
    Prova.certo("Segredo em texto claro e' MEDIA",
                severidadeDe("SEGREDO_EM_TEXTO_CLARO") == RegrasSensiveis.Severidade.MEDIA);
  }

  private static RegrasSensiveis.Severidade severidadeDe(String rotulo) {
    for (RegrasSensiveis.Regra regra : RegrasSensiveis.regras()) {
      if (regra.getRotulo().equals(rotulo)) {
        return regra.getSeveridade();
      }
    }
    return null;
  }
}
