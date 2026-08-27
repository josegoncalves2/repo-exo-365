package br.pmo.dlp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mascaramento de dado sensivel preservando o FORMATO.
 *
 * <p><b>POR QUE PRESERVAR O FORMATO.</b> A tentacao e' trocar o achado por
 * {@code [REDIGIDO]}. Isso destroi o documento: um relatorio com trezentas
 * linhas de {@code [REDIGIDO]} perde o alinhamento, perde a conferencia de
 * colunas e o servidor que precisa dele nao consegue trabalhar -- entao ele
 * pede a versao sem mascara, e a mascara vira teatro. Mantendo
 * {@code ***.***.789-01} o documento continua legivel, a coluna continua
 * alinhada, e ainda da' para conferir de qual registro se trata.
 *
 * <p><b>QUANTO REVELAR: A LINHA E' A REIDENTIFICACAO.</b> Cada tipo revela o
 * minimo que sustenta conferencia sem permitir reconstruir o numero:
 *
 * <ul>
 *   <li><b>CPF</b> revela os digitos 7 a 9 e o verificador. E' a convencao de
 *       tribunal e de Diario Oficial no Brasil ({@code ***.***.789-01}), ja'
 *       aceita por quem le. Sobram 10^6 combinacoes para o corpo -- e o modulo
 *       11 nao ajuda o atacante, porque o verificador ja' esta' consistente.</li>
 *   <li><b>Cartao</b> revela SO' os quatro ultimos. Nao os seis primeiros: o
 *       BIN identifica o emissor e, combinado com os quatro finais, reduz muito
 *       o espaco de busca. PCI-DSS permite 6+4; aqui vale a regra mais apertada,
 *       porque nada neste portal precisa saber a bandeira.</li>
 *   <li><b>E-mail</b> mantem o dominio e revela a primeira letra do usuario.
 *       O dominio quase nunca e' o segredo (e' a prefeitura); o nome e'.</li>
 *   <li><b>Telefone</b> mantem o DDD -- informacao geografica, ja' publica pelo
 *       endereco do orgao -- e revela os dois ultimos digitos.</li>
 *   <li><b>Segredo em texto claro</b> nao revela NADA do valor. Senha parcial e'
 *       senha entregue: encurta um ataque de dicionario em ordens de grandeza.
 *       Preserva-se apenas o rotulo ({@code senha=}) para o autor saber qual
 *       linha corrigir.</li>
 * </ul>
 *
 * <p>Sem estado e sem I/O.
 */
public final class Mascarador {

  /** O caractere de mascara. Fixo: mascara configuravel vira mascara divergente
   *  entre relatorios, e conferencia entre dois relatorios deixa de funcionar. */
  private static final char TAPA = '*';

  private Mascarador() {
  }

  /**
   * Mascara UM trecho, escolhendo a regra pelo rotulo.
   *
   * @param rotulo rotulo da regra que detectou ({@code CPF}, {@code EMAIL}, ...)
   * @param bruto  o trecho como estava no documento
   * @return o trecho mascarado, com o mesmo comprimento e a mesma pontuacao
   */
  public static String mascarar(String rotulo, String bruto) {
    if (bruto == null || bruto.isEmpty()) {
      return bruto;
    }
    if (rotulo == null) {
      return revelarUltimos(bruto, 0);
    }
    switch (rotulo) {
      case "CPF":
        // 11 digitos: esconde 1-6, revela 7-9 e os dois verificadores.
        return revelarFaixaDeDigitos(bruto, 6, 11);
      case "CNPJ":
        // 14 digitos: revela a ordem do estabelecimento (9-12) e esconde a raiz.
        return revelarFaixaDeDigitos(bruto, 8, 12);
      case "CARTAO_CREDITO":
        return revelarUltimos(bruto, 4);
      case "TITULO_ELEITOR":
      case "PIS_PASEP":
      case "CNH":
        return revelarUltimos(bruto, 2);
      case "CHAVE_PIX_ALEATORIA":
        // UUID: revela so' o ultimo bloco, que sozinho nao e' chave valida.
        return revelarUltimos(bruto, 12);
      case "SEGREDO_EM_TEXTO_CLARO":
        return mascararSegredo(bruto);
      case "EMAIL":
        return mascararEmail(bruto);
      case "TELEFONE":
        // Mantem o DDD (2 primeiros digitos) e os 2 ultimos.
        return revelarBordasDeDigitos(bruto, 2, 2);
      case "CEP":
        // Revela o prefixo de 5 -- que e' o municipio, ja' publico -- e esconde
        // o sufixo, que e' o logradouro.
        return revelarFaixaDeDigitos(bruto, 0, 5);
      default:
        return revelarUltimos(bruto, 2);
    }
  }

  /**
   * Reescreve o texto inteiro com todos os achados mascarados.
   *
   * <p><b>A ORDEM DA SUBSTITUICAO E' O CONTRATO DESTE METODO, E ERRAR NELA
   * VAZA EM SILENCIO.</b> Substituir do inicio para o fim desloca todos os
   * indices seguintes assim que uma troca muda de comprimento -- e a mascara de
   * segredo muda: {@code senha=umaSenhaLonga} (16 chars) vira
   * {@code senha= ***} (10). Dai' em diante, cada indice aponta para o lugar
   * errado, e o CPF que vinha depois sai INTACTO num texto que se anuncia
   * mascarado.
   *
   * <p>Nao basta ir de tras para frente POR ACHADO: {@link Varredura} entrega
   * os achados em ordem de SEVERIDADE, nao de posicao. Percorrer a lista ao
   * contrario comeca pelo menos grave, que pode estar no COMECO do texto -- e o
   * defeito reaparece igual. (Foi assim que ele existiu neste arquivo ate'
   * 2026-08-27, e foi assim que a prova o pegou.)
   *
   * <p>Entao: todas as ocorrencias de todos os achados sao ACHATADAS numa lista
   * so' e ordenadas por posicao DECRESCENTE. Ai' sim cada indice ainda vale
   * quando chega a vez dele, qualquer que seja a ordem dos achados.
   *
   * @throws IllegalArgumentException se algum achado nao couber no texto -- e'
   *         sinal de que laudo e texto vieram de origens diferentes. Falhar alto
   *         e' obrigatorio aqui: devolver texto PARCIALMENTE mascarado seria
   *         entregar um vazamento com aparencia de documento protegido, e quem
   *         chama trataria como seguro.
   */
  public static String mascararTexto(String texto, List<Achado> achados) {
    if (texto == null || achados == null || achados.isEmpty()) {
      return texto;
    }

    List<Trecho> trechos = new ArrayList<>();
    for (Achado achado : achados) {
      for (Ocorrencia ocorrencia : achado.getOcorrencias()) {
        if (ocorrencia.getFim() > texto.length()) {
          throw new IllegalArgumentException(
              "achado de " + achado.getRotulo() + " termina em " + ocorrencia.getFim()
              + ", fora de um texto de " + texto.length()
              + " caracteres: laudo e texto nao sao do mesmo documento");
        }
        trechos.add(new Trecho(achado.getRotulo(), ocorrencia));
      }
    }
    trechos.sort(Comparator.comparingInt((Trecho t) -> t.ocorrencia.getInicio()).reversed());

    StringBuilder sb = new StringBuilder(texto);
    for (Trecho trecho : trechos) {
      sb.replace(trecho.ocorrencia.getInicio(),
                 trecho.ocorrencia.getFim(),
                 mascarar(trecho.rotulo, trecho.ocorrencia.getBruto()));
    }
    return sb.toString();
  }

  /** Par rotulo+ocorrencia, vivo so' durante {@link #mascararTexto}. */
  private static final class Trecho {
    private final String rotulo;
    private final Ocorrencia ocorrencia;

    Trecho(String rotulo, Ocorrencia ocorrencia) {
      this.rotulo = rotulo;
      this.ocorrencia = ocorrencia;
    }
  }

  // ===========================================================================
  // Estrategias
  // ===========================================================================

  /**
   * Revela os digitos de indice {@code de} (inclusive) ate {@code ate}
   * (exclusivo), CONTANDO SO' DIGITOS -- pontuacao nao entra na conta e nunca e'
   * mascarada. E' isso que faz {@code 123.456.789-01} e {@code 12345678901}
   * revelarem exatamente os mesmos digitos.
   */
  private static String revelarFaixaDeDigitos(String bruto, int de, int ate) {
    StringBuilder sb = new StringBuilder(bruto.length());
    int indiceDigito = 0;
    for (int i = 0; i < bruto.length(); i++) {
      char c = bruto.charAt(i);
      if (Character.isDigit(c)) {
        sb.append(indiceDigito >= de && indiceDigito < ate ? c : TAPA);
        indiceDigito++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Revela os {@code n} ultimos digitos (ou caracteres, se nao houver digito). */
  private static String revelarUltimos(String bruto, int n) {
    int totalDigitos = contarDigitos(bruto);
    if (totalDigitos == 0) {
      return revelarUltimosCaracteres(bruto, n);
    }
    return revelarFaixaDeDigitos(bruto, Math.max(0, totalDigitos - n), totalDigitos);
  }

  /** Revela os {@code inicio} primeiros e os {@code fim} ultimos digitos. */
  private static String revelarBordasDeDigitos(String bruto, int inicio, int fim) {
    int total = contarDigitos(bruto);
    StringBuilder sb = new StringBuilder(bruto.length());
    int indiceDigito = 0;
    for (int i = 0; i < bruto.length(); i++) {
      char c = bruto.charAt(i);
      if (Character.isDigit(c)) {
        boolean revela = indiceDigito < inicio || indiceDigito >= total - fim;
        sb.append(revela ? c : TAPA);
        indiceDigito++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String revelarUltimosCaracteres(String bruto, int n) {
    int corte = Math.max(0, bruto.length() - n);
    StringBuilder sb = new StringBuilder(bruto.length());
    for (int i = 0; i < bruto.length(); i++) {
      char c = bruto.charAt(i);
      sb.append(i < corte && !ehSeparador(c) ? TAPA : c);
    }
    return sb.toString();
  }

  private static boolean ehSeparador(char c) {
    return c == '-' || c == '.' || c == '/' || c == ' ' || c == '(' || c == ')';
  }

  private static int contarDigitos(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (Character.isDigit(s.charAt(i))) {
        n++;
      }
    }
    return n;
  }

  /**
   * {@code senha: correiacavalobateriagrampo} vira {@code senha: ***}.
   * O valor nao vaza nem no comprimento: tres asteriscos SEMPRE, porque o
   * comprimento da senha ja' e' informacao util para quem ataca.
   */
  private static String mascararSegredo(String bruto) {
    int corte = -1;
    for (int i = 0; i < bruto.length(); i++) {
      char c = bruto.charAt(i);
      if (c == ':' || c == '=') {
        corte = i;
        break;
      }
    }
    if (corte < 0) {
      return "***";
    }
    return bruto.substring(0, corte + 1) + " ***";
  }

  /** {@code joao.silva@pmo.gov.br} vira {@code j*********@pmo.gov.br}. */
  private static String mascararEmail(String bruto) {
    int arroba = bruto.indexOf('@');
    if (arroba <= 0) {
      return revelarUltimos(bruto, 0);
    }
    StringBuilder sb = new StringBuilder(bruto.length());
    sb.append(bruto.charAt(0));
    for (int i = 1; i < arroba; i++) {
      sb.append(TAPA);
    }
    sb.append(bruto, arroba, bruto.length());
    return sb.toString();
  }
}
