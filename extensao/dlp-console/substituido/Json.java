package br.pmo.dlpconsole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de JSON completo o bastante para o que a API do DLP devolve.
 *
 * <p><b>POR QUE ESCREVER UM E NAO USAR JACKSON.</b> Este codigo roda no
 * classpath do portal, ao lado de centenas de jars. Acrescentar uma
 * biblioteca de serializacao aumenta a chance de conflito de versao -- que e'
 * a classe de defeito mais cara deste projeto. O formato lido aqui e'
 * produzido pelo nosso proprio servico, entao o contrato e' nosso dos dois
 * lados.
 *
 * <p><b>POR QUE UM PARSER DE VERDADE E NAO indexOf.</b> O {@code ClienteDlp}
 * le seis campos escalares procurando aspas com {@code indexOf}, e isso basta
 * para um veredito. O console precisa de listas de objetos aninhados
 * (incidentes com evidencia, trilha e anotacoes): com busca textual, um valor
 * que contivesse {@code "regra":} dentro de um nome de arquivo alteraria a
 * leitura da tela. Um analisador recursivo custa duzentas linhas e nao tem
 * essa classe de erro.
 *
 * <p>Produz apenas quatro tipos: {@link Map}, {@link List}, {@link String} e
 * {@link Double}, mais {@link Boolean} e nulo. Nao ha' vinculo a classes de
 * dominio -- a tela le por chave, e um campo novo na API nao quebra a
 * compilacao aqui.
 */
public final class Json {

  private final String texto;
  private int posicao;

  private Json(String texto) {
    this.texto = texto;
  }

  /** Analisa um documento inteiro. Sobra depois do valor e' erro. */
  public static Object ler(String texto) {
    if (texto == null) {
      return null;
    }
    Json j = new Json(texto);
    j.espacos();
    Object valor = j.valor();
    j.espacos();
    if (j.posicao < texto.length()) {
      throw new IllegalArgumentException(
          "sobra depois do valor na posicao " + j.posicao);
    }
    return valor;
  }

  /** Le e devolve um objeto; qualquer outra coisa vira mapa vazio. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> objeto(String texto) {
    try {
      Object v = ler(texto);
      return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    } catch (RuntimeException e) {
      return new LinkedHashMap<>();
    }
  }

  // ------------------------------------------------------------- acessores
  @SuppressWarnings("unchecked")
  public static Map<String, Object> mapa(Object alvo, String chave) {
    Object v = alvo instanceof Map ? ((Map<String, Object>) alvo).get(chave) : null;
    return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> lista(Object alvo, String chave) {
    Object v = alvo instanceof Map ? ((Map<String, Object>) alvo).get(chave) : null;
    return v instanceof List ? (List<Object>) v : new ArrayList<>();
  }

  @SuppressWarnings("unchecked")
  public static String texto(Object alvo, String chave) {
    Object v = alvo instanceof Map ? ((Map<String, Object>) alvo).get(chave) : null;
    if (v == null) {
      return "";
    }
    if (v instanceof String) {
      return (String) v;
    }
    if (v instanceof Double) {
      double d = (Double) v;
      return d == Math.floor(d) && !Double.isInfinite(d)
          ? String.valueOf((long) d) : String.valueOf(d);
    }
    return String.valueOf(v);
  }

  @SuppressWarnings("unchecked")
  public static boolean logico(Object alvo, String chave, boolean padrao) {
    Object v = alvo instanceof Map ? ((Map<String, Object>) alvo).get(chave) : null;
    return v instanceof Boolean ? (Boolean) v : padrao;
  }

  @SuppressWarnings("unchecked")
  public static long inteiro(Object alvo, String chave, long padrao) {
    Object v = alvo instanceof Map ? ((Map<String, Object>) alvo).get(chave) : null;
    if (v instanceof Double) {
      return (long) (double) (Double) v;
    }
    if (v instanceof String) {
      try {
        return Long.parseLong(((String) v).trim());
      } catch (NumberFormatException e) {
        return padrao;
      }
    }
    return padrao;
  }

  /** Junta uma lista de textos com virgula. Usado nas colunas da tabela. */
  public static String juntar(List<Object> itens, String separador) {
    StringBuilder b = new StringBuilder();
    for (Object o : itens) {
      if (b.length() > 0) {
        b.append(separador);
      }
      b.append(o instanceof String ? (String) o : String.valueOf(o));
    }
    return b.toString();
  }

  // ------------------------------------------------------------- analisador
  private Object valor() {
    if (posicao >= texto.length()) {
      throw new IllegalArgumentException("documento vazio");
    }
    char c = texto.charAt(posicao);
    switch (c) {
      case '{': return objetoInterno();
      case '[': return listaInterna();
      case '"': return textoInterno();
      case 't': return literal("true", Boolean.TRUE);
      case 'f': return literal("false", Boolean.FALSE);
      case 'n': return literal("null", null);
      default: return numeroInterno();
    }
  }

  private Map<String, Object> objetoInterno() {
    Map<String, Object> mapa = new LinkedHashMap<>();
    posicao++;                                    // consome '{'
    espacos();
    if (espia() == '}') {
      posicao++;
      return mapa;
    }
    while (true) {
      espacos();
      if (espia() != '"') {
        throw new IllegalArgumentException(
            "chave de objeto tem de ser texto, posicao " + posicao);
      }
      String chave = textoInterno();
      espacos();
      if (espia() != ':') {
        throw new IllegalArgumentException("faltou ':' na posicao " + posicao);
      }
      posicao++;
      espacos();
      mapa.put(chave, valor());
      espacos();
      char c = espia();
      posicao++;
      if (c == '}') {
        return mapa;
      }
      if (c != ',') {
        throw new IllegalArgumentException(
            "esperado ',' ou '}' na posicao " + (posicao - 1));
      }
    }
  }

  private List<Object> listaInterna() {
    List<Object> itens = new ArrayList<>();
    posicao++;                                    // consome '['
    espacos();
    if (espia() == ']') {
      posicao++;
      return itens;
    }
    while (true) {
      espacos();
      itens.add(valor());
      espacos();
      char c = espia();
      posicao++;
      if (c == ']') {
        return itens;
      }
      if (c != ',') {
        throw new IllegalArgumentException(
            "esperado ',' ou ']' na posicao " + (posicao - 1));
      }
    }
  }

  private String textoInterno() {
    posicao++;                                    // consome a aspa de abertura
    StringBuilder b = new StringBuilder();
    while (posicao < texto.length()) {
      char c = texto.charAt(posicao++);
      if (c == '"') {
        return b.toString();
      }
      if (c != '\\') {
        b.append(c);
        continue;
      }
      if (posicao >= texto.length()) {
        break;
      }
      char e = texto.charAt(posicao++);
      switch (e) {
        case 'n': b.append('\n'); break;
        case 'r': b.append('\r'); break;
        case 't': b.append('\t'); break;
        case 'b': b.append('\b'); break;
        case 'f': b.append('\f'); break;
        case 'u':
          if (posicao + 4 > texto.length()) {
            throw new IllegalArgumentException("escape \\u truncado");
          }
          b.append((char) Integer.parseInt(texto.substring(posicao, posicao + 4), 16));
          posicao += 4;
          break;
        default: b.append(e);
      }
    }
    throw new IllegalArgumentException("texto sem aspa de fechamento");
  }

  private Object numeroInterno() {
    int inicio = posicao;
    while (posicao < texto.length()
           && "+-0123456789.eE".indexOf(texto.charAt(posicao)) >= 0) {
      posicao++;
    }
    if (inicio == posicao) {
      throw new IllegalArgumentException(
          "valor inesperado na posicao " + posicao + ": '"
          + texto.charAt(posicao) + "'");
    }
    return Double.valueOf(texto.substring(inicio, posicao));
  }

  private Object literal(String palavra, Object valor) {
    if (!texto.startsWith(palavra, posicao)) {
      throw new IllegalArgumentException(
          "literal invalido na posicao " + posicao);
    }
    posicao += palavra.length();
    return valor;
  }

  private char espia() {
    if (posicao >= texto.length()) {
      throw new IllegalArgumentException("documento terminou antes da hora");
    }
    return texto.charAt(posicao);
  }

  private void espacos() {
    while (posicao < texto.length()
           && Character.isWhitespace(texto.charAt(posicao))) {
      posicao++;
    }
  }

  // ------------------------------------------------------------- serializacao
  /**
   * Serializa de volta, com recuo, o que {@link #ler(String)} produziu.
   *
   * <p>E' o que permite a tela de politica mostrar o JSON legivel para edicao
   * e devolve-lo ao servico. Sem isto, o administrador editaria uma linha
   * unica de dois mil caracteres -- o que na pratica significa nao editar.
   */
  public static String bonito(Object valor) {
    StringBuilder b = new StringBuilder(2048);
    escreverValor(b, valor, 0);
    return b.toString();
  }

  /** Le e reescreve com recuo. Texto invalido volta como veio. */
  public static String formatar(String bruto) {
    try {
      return bonito(ler(bruto));
    } catch (RuntimeException e) {
      return bruto == null ? "" : bruto;
    }
  }

  @SuppressWarnings("unchecked")
  private static void escreverValor(StringBuilder b, Object valor, int nivel) {
    if (valor == null) {
      b.append("null");
    } else if (valor instanceof String) {
      b.append(escrever((String) valor));
    } else if (valor instanceof Boolean) {
      b.append(valor);
    } else if (valor instanceof Double) {
      double d = (Double) valor;
      if (d == Math.floor(d) && !Double.isInfinite(d)) {
        b.append((long) d);
      } else {
        b.append(d);
      }
    } else if (valor instanceof Map) {
      Map<String, Object> mapa = (Map<String, Object>) valor;
      if (mapa.isEmpty()) {
        b.append("{}");
        return;
      }
      b.append("{\n");
      int restantes = mapa.size();
      for (Map.Entry<String, Object> e : mapa.entrySet()) {
        recuo(b, nivel + 1);
        b.append(escrever(e.getKey())).append(": ");
        escreverValor(b, e.getValue(), nivel + 1);
        b.append(--restantes > 0 ? ",\n" : "\n");
      }
      recuo(b, nivel);
      b.append('}');
    } else if (valor instanceof List) {
      List<Object> lista = (List<Object>) valor;
      if (lista.isEmpty()) {
        b.append("[]");
        return;
      }
      b.append("[\n");
      for (int i = 0; i < lista.size(); i++) {
        recuo(b, nivel + 1);
        escreverValor(b, lista.get(i), nivel + 1);
        b.append(i < lista.size() - 1 ? ",\n" : "\n");
      }
      recuo(b, nivel);
      b.append(']');
    } else {
      b.append(escrever(String.valueOf(valor)));
    }
  }

  private static void recuo(StringBuilder b, int nivel) {
    for (int i = 0; i < nivel; i++) {
      b.append("  ");
    }
  }

  /** Escreve um texto como literal JSON. Usado para montar o corpo do POST. */
  public static String escrever(String valor) {
    if (valor == null) {
      return "null";
    }
    StringBuilder b = new StringBuilder(valor.length() + 16).append('"');
    for (int i = 0; i < valor.length(); i++) {
      char c = valor.charAt(i);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    return b.append('"').toString();
  }
}
