package br.pmo.dlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalogo de dados pessoais e sensiveis brasileiros, para o DLP.
 *
 * <p><b>POR QUE CADA REGRA TEM VALIDADOR, E NAO SO' REGEX.</b> Uma regex de CPF
 * escrita como {@code \d{11}} casa com numero de protocolo, matricula, codigo de
 * barras truncado, telefone com DDI e qualquer sequencia de onze digitos. Ligar
 * DLP com regra assim num acervo em producao poe em quarentena documento
 * legitimo em massa, e o efeito colateral e' pior do que a exposicao que se
 * queria evitar: some documento de servidor que nao foi avisado, e a
 * administracao desliga o DLP inteiro no dia seguinte.
 *
 * <p>Por isso toda regra de severidade ALTA confere DIGITO VERIFICADOR. Um CPF
 * so' e' CPF se o modulo 11 fechar; um cartao so' e' cartao se passar no Luhn.
 * O falso positivo cai de "qualquer sequencia de digitos" para "sequencia que
 * satisfaz a aritmetica do documento" -- ordens de grandeza menos ruido.
 *
 * <p><b>SEVERIDADE.</b> E-mail e telefone aparecem em praticamente todo
 * documento administrativo; poe-los em quarentena e' inviabilizar o portal.
 * Eles entram como BAIXA: sao CONTADOS e RELATADOS, mas nao disparam quarentena
 * sozinhos. Quem decide o corte e' {@code exo.dlp.regex.severidadeMinima}.
 *
 * <p>Sem estado e sem I/O: da' para exercitar cada regra isoladamente.
 */
public final class RegrasSensiveis {

  /** Severidade de uma regra. A ordem do enum E' a ordem de comparacao. */
  public enum Severidade {
    BAIXA, MEDIA, ALTA
  }

  /** Uma regra: como achar candidatos no texto e como confirmar que sao reais. */
  public static final class Regra {
    private final String rotulo;
    private final Pattern padrao;
    private final Severidade severidade;
    private final Validador validador;

    Regra(String rotulo, String regex, Severidade severidade, Validador validador) {
      this.rotulo = rotulo;
      this.padrao = Pattern.compile(regex);
      this.severidade = severidade;
      this.validador = validador;
    }

    public String getRotulo() {
      return rotulo;
    }

    public Severidade getSeveridade() {
      return severidade;
    }

    /** Quantas ocorrencias CONFIRMADAS desta regra existem no texto. */
    int contarEm(String texto) {
      Matcher m = padrao.matcher(texto);
      int achados = 0;
      while (m.find()) {
        if (validador == null || validador.confirma(m.group())) {
          achados++;
        }
      }
      return achados;
    }

    /**
     * As ocorrencias CONFIRMADAS, com a posicao de cada uma.
     *
     * <p>Acrescimo de 2026-08-27. {@link #contarEm} continua existindo e
     * intacto: quem so' quer contagem nao paga o custo de montar a lista, e
     * nenhum chamador antigo muda de comportamento. Este metodo e' o que
     * alimenta mascaramento e desduplicacao, que precisam saber ONDE.
     */
    List<Ocorrencia> ocorrenciasEm(String texto) {
      List<Ocorrencia> encontradas = new ArrayList<>();
      if (texto == null || texto.isEmpty()) {
        return encontradas;
      }
      Matcher m = padrao.matcher(texto);
      while (m.find()) {
        if (validador == null || validador.confirma(m.group())) {
          encontradas.add(new Ocorrencia(m.group(), m.start(), m.end()));
        }
      }
      return encontradas;
    }
  }

  /** Confirmacao aritmetica do candidato casado pela regex. */
  interface Validador {
    boolean confirma(String bruto);
  }

  // ---------------------------------------------------------------------------
  // Fronteiras (?<!\d) e (?!\d): impedem que a regra de CPF (11 digitos) case
  // com um PEDACO de uma sequencia de 14 (que seria um CNPJ) ou de 16 (cartao).
  // Sem elas uma unica linha de cartao geraria tres deteccoes diferentes.
  // ---------------------------------------------------------------------------
  private static final List<Regra> REGRAS = Collections.unmodifiableList(new ArrayList<Regra>() {
    {
      add(new Regra("CPF",
                    "(?<![\\d])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::cpfValido));

      add(new Regra("CNPJ",
                    "(?<![\\d])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::cnpjValido));

      add(new Regra("CARTAO_CREDITO",
                    "(?<![\\d])(?:\\d[ .-]?){12,18}\\d(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::luhnValido));

      add(new Regra("TITULO_ELEITOR",
                    "(?<![\\d])\\d{4}\\.?\\d{4}\\.?\\d{4}(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::tituloEleitorValido));

      add(new Regra("PIS_PASEP",
                    "(?<![\\d])\\d{3}\\.?\\d{5}\\.?\\d{2}-?\\d{1}(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::pisValido));

      add(new Regra("CNH",
                    "(?<![\\d])\\d{11}(?![\\d])",
                    Severidade.ALTA,
                    RegrasSensiveis::cnhValida));

      // Chave PIX aleatoria: UUID. Nao ha digito verificador; o proprio formato
      // ja' e' especifico o bastante para nao colidir com texto comum.
      add(new Regra("CHAVE_PIX_ALEATORIA",
                    "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b",
                    Severidade.MEDIA,
                    null));

      // Segredo em texto claro. Nao e' dado pessoal, e' credencial vazada --
      // e num portal corporativo vale tanto quanto um CPF.
      add(new Regra("SEGREDO_EM_TEXTO_CLARO",
                    "(?i)\\b(senha|password|passwd|api[_-]?key|secret|token|chave[_-]?privada)\\b\\s*[:=]\\s*\\S{4,}",
                    Severidade.MEDIA,
                    null));

      add(new Regra("EMAIL",
                    "(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b",
                    Severidade.BAIXA,
                    null));

      add(new Regra("TELEFONE",
                    "(?<![\\d])(?:\\+55[ .-]?)?\\(?\\d{2}\\)?[ .-]?9?\\d{4}[ .-]?\\d{4}(?![\\d])",
                    Severidade.BAIXA,
                    null));

      add(new Regra("CEP",
                    "(?<![\\d])\\d{5}-?\\d{3}(?![\\d])",
                    Severidade.BAIXA,
                    null));
    }
  });

  private RegrasSensiveis() {
  }

  /**
   * O catalogo, em ordem de prioridade. Acrescimo de 2026-08-27, para que o
   * motor ({@link Varredura}) percorra as regras sem duplicar a lista.
   *
   * <p>A ORDEM E' CONTRATO, nao detalhe: e' ela que decide quem sobrevive
   * quando dois achados ocupam o mesmo trecho -- CPF antes de CNH, ambos antes
   * de TELEFONE. Devolve lista imutavel: catalogo alterado em tempo de execucao
   * seria regra de seguranca mudando debaixo de quem ja' esta' varrendo.
   */
  public static List<Regra> regras() {
    return REGRAS;
  }

  /**
   * Roda o catalogo inteiro sobre o texto.
   *
   * @return rotulo -> quantidade confirmada, na ordem do catalogo, so' com o que
   *         teve pelo menos uma ocorrencia. Mapa vazio quando nada casou.
   */
  public static Map<String, Integer> detectar(String texto) {
    Map<String, Integer> achados = new LinkedHashMap<>();
    if (texto == null || texto.isEmpty()) {
      return achados;
    }
    for (Regra regra : REGRAS) {
      int n = regra.contarEm(texto);
      if (n > 0) {
        achados.put(regra.getRotulo(), n);
      }
    }
    return achados;
  }

  /**
   * Rotulos detectados cuja severidade alcanca o corte. E' o conjunto que
   * justifica quarentena; o resto e' so' relatorio.
   */
  public static Set<String> acimaDoCorte(Map<String, Integer> achados, Severidade corte) {
    Set<String> selecionados = new LinkedHashSet<>();
    for (Regra regra : REGRAS) {
      if (achados.containsKey(regra.getRotulo())
          && regra.getSeveridade().compareTo(corte) >= 0) {
        selecionados.add(regra.getRotulo());
      }
    }
    return selecionados;
  }

  /** Le a severidade de uma configuracao textual, com padrao seguro. */
  public static Severidade severidadeDe(String texto, Severidade padrao) {
    if (texto == null || texto.trim().isEmpty()) {
      return padrao;
    }
    try {
      return Severidade.valueOf(texto.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // Configuracao errada nao pode virar "quarentena de tudo": cai no padrao.
      return padrao;
    }
  }

  // ===========================================================================
  // Validadores aritmeticos
  // ===========================================================================

  private static String soDigitos(String bruto) {
    return bruto.replaceAll("\\D", "");
  }

  /** Sequencia de digitos identicos (000..., 111...) passa em quase todo
   *  algoritmo de digito verificador e nunca e' documento real. */
  private static boolean todosIguais(String d) {
    for (int i = 1; i < d.length(); i++) {
      if (d.charAt(i) != d.charAt(0)) {
        return false;
      }
    }
    return true;
  }

  static boolean cpfValido(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() != 11 || todosIguais(d)) {
      return false;
    }
    for (int digito = 9; digito < 11; digito++) {
      int soma = 0;
      for (int i = 0; i < digito; i++) {
        soma += (d.charAt(i) - '0') * ((digito + 1) - i);
      }
      int resto = soma % 11;
      int esperado = (resto < 2) ? 0 : 11 - resto;
      if ((d.charAt(digito) - '0') != esperado) {
        return false;
      }
    }
    return true;
  }

  static boolean cnpjValido(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() != 14 || todosIguais(d)) {
      return false;
    }
    int[] pesos1 = { 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };
    int[] pesos2 = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };
    if (!conferePeso(d, 12, pesos1) || !conferePeso(d, 13, pesos2)) {
      return false;
    }
    return true;
  }

  private static boolean conferePeso(String d, int posicaoDigito, int[] pesos) {
    int soma = 0;
    for (int i = 0; i < pesos.length; i++) {
      soma += (d.charAt(i) - '0') * pesos[i];
    }
    int resto = soma % 11;
    int esperado = (resto < 2) ? 0 : 11 - resto;
    return (d.charAt(posicaoDigito) - '0') == esperado;
  }

  /** Luhn (ISO/IEC 7812), o algoritmo de todo cartao de pagamento. */
  static boolean luhnValido(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() < 13 || d.length() > 19 || todosIguais(d)) {
      return false;
    }
    int soma = 0;
    boolean dobra = false;
    for (int i = d.length() - 1; i >= 0; i--) {
      int n = d.charAt(i) - '0';
      if (dobra) {
        n *= 2;
        if (n > 9) {
          n -= 9;
        }
      }
      soma += n;
      dobra = !dobra;
    }
    return soma % 10 == 0;
  }

  static boolean tituloEleitorValido(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() != 12 || todosIguais(d)) {
      return false;
    }
    // Os dois digitos finais do titulo dependem da UF (posicoes 8-9).
    int uf = Integer.parseInt(d.substring(8, 10));
    if (uf < 1 || uf > 28) {
      return false;
    }
    int soma1 = 0;
    for (int i = 0; i < 8; i++) {
      soma1 += (d.charAt(i) - '0') * (i + 2);
    }
    int dv1 = soma1 % 11;
    if (dv1 == 10) {
      dv1 = 0;
    }
    if ((d.charAt(10) - '0') != dv1) {
      return false;
    }
    int soma2 = (d.charAt(8) - '0') * 7 + (d.charAt(9) - '0') * 8 + dv1 * 9;
    int dv2 = soma2 % 11;
    if (dv2 == 10) {
      dv2 = 0;
    }
    return (d.charAt(11) - '0') == dv2;
  }

  static boolean pisValido(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() != 11 || todosIguais(d)) {
      return false;
    }
    int[] pesos = { 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };
    int soma = 0;
    for (int i = 0; i < 10; i++) {
      soma += (d.charAt(i) - '0') * pesos[i];
    }
    int resto = soma % 11;
    int dv = (resto < 2) ? 0 : 11 - resto;
    return (d.charAt(10) - '0') == dv;
  }

  /**
   * CNH tem 11 digitos, como o CPF, e o mesmo formato bruto. O algoritmo e'
   * outro, entao um numero pode ser CNH valida sem ser CPF valido e vice-versa.
   * Ambas as regras rodam sobre o mesmo candidato de proposito: qualquer uma
   * que feche ja' e' dado pessoal.
   */
  static boolean cnhValida(String bruto) {
    String d = soDigitos(bruto);
    if (d.length() != 11 || todosIguais(d)) {
      return false;
    }
    int soma = 0;
    for (int i = 0, peso = 9; i < 9; i++, peso--) {
      soma += (d.charAt(i) - '0') * peso;
    }
    int dv1 = soma % 11;
    int deslocamento = 0;
    if (dv1 >= 10) {
      dv1 = 0;
      deslocamento = 2;
    }

    soma = 0;
    for (int i = 0, peso = 1; i < 9; i++, peso++) {
      soma += (d.charAt(i) - '0') * peso;
    }
    int dv2 = soma % 11;
    if (dv2 >= 10) {
      dv2 = 0;
    } else {
      dv2 = dv2 - deslocamento;
      if (dv2 < 0) {
        dv2 += 11;
      }
    }
    return (d.charAt(9) - '0') == dv1 && (d.charAt(10) - '0') == dv2;
  }
}
