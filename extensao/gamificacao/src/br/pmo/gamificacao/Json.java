package br.pmo.gamificacao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de JSON minimo e ESTRITO, em JDK puro.
 *
 * <p>POR QUE ESCREVER UM. O nucleo nao pode depender de Jackson nem de Gson: a
 * regra desta extensao e' compilar e se provar com {@code javac} e {@code java}
 * num servidor sem saida para a internet. Duzentas linhas resolvem o que se
 * precisa aqui -- ler a resposta de uma API e o corpo de um webhook.
 *
 * <p>POR QUE ESTRITO. A tentacao e' ser tolerante e devolver algo mesmo com
 * entrada suja. Seria um erro: este leitor processa bytes que chegam pela rede,
 * e resposta que nao e' JSON valido significa quase sempre que a requisicao NAO
 * chegou onde se pensava -- portal cativo de hotel, proxy corporativo com pagina
 * de bloqueio, servidor devolvendo HTML de erro com status 200. Um leitor
 * tolerante devolveria mapa vazio, o conector leria "sem erro" e diria ao
 * operador que a credencial esta' valida quando ninguem falou com o provedor.
 * Aqui, entrada invalida levanta {@link JsonInvalidoException} e o conector
 * responde {@code FALHOU} com codigo {@code json.malformado}.
 *
 * <p>Ha' um teto de profundidade: JSON aninhado milhares de niveis derruba um
 * analisador recursivo por estouro de pilha, e o corpo vem de fora.
 */
public final class Json {

  /** Entrada que nao e' JSON valido. */
  public static final class JsonInvalidoException extends Exception {
    private static final long serialVersionUID = 1L;

    public JsonInvalidoException(String mensagem) {
      super(mensagem);
    }
  }

  /** Teto de aninhamento; acima disso a entrada e' recusada, nao estourada. */
  private static final int PROFUNDIDADE_MAXIMA = 64;

  private final String texto;

  private int pos;

  private int profundidade;

  private Json(String texto) {
    this.texto = texto;
  }

  /**
   * Interpreta um documento JSON completo.
   *
   * @return {@code Map<String,Object>}, {@code List<Object>}, {@code String},
   *         {@code Double}, {@code Boolean} ou {@code null}
   * @throws JsonInvalidoException se sobrar lixo depois do valor, ou se a
   *         sintaxe estiver errada em qualquer ponto
   */
  public static Object ler(String texto) throws JsonInvalidoException {
    if (texto == null) {
      throw new JsonInvalidoException("entrada nula");
    }
    Json j = new Json(texto);
    j.pularBrancos();
    Object valor = j.lerValor();
    j.pularBrancos();
    if (j.pos != texto.length()) {
      throw new JsonInvalidoException("lixo depois do valor na posicao " + j.pos);
    }
    return valor;
  }

  /**
   * Le um documento que TEM de ser objeto.
   *
   * <p>Existe porque toda resposta de API tratada aqui e' objeto; receber um
   * vetor ou um numero solto onde se esperava objeto e' sinal de que se falou
   * com outra coisa, e isso precisa virar falha, nao {@code ClassCastException}
   * tres metodos adiante.
   */
  public static Map<String, Object> lerObjeto(String texto) throws JsonInvalidoException {
    Object valor = ler(texto);
    if (!(valor instanceof Map)) {
      throw new JsonInvalidoException("esperado objeto JSON, veio "
          + (valor == null ? "null" : valor.getClass().getSimpleName()));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> mapa = (Map<String, Object>) valor;
    return mapa;
  }

  /**
   * Texto de um campo de primeiro nivel.
   *
   * @return {@code null} se ausente ou se nao for texto -- o chamador ja' trata
   *         ausencia, e distinguir "ausente" de "presente com outro tipo" nao
   *         muda nenhuma decisao nesta extensao
   */
  public static String texto(Map<String, Object> objeto, String chave) {
    Object v = objeto == null ? null : objeto.get(chave);
    return v instanceof String ? (String) v : null;
  }

  /** Booleano de um campo de primeiro nivel; {@code false} se ausente. */
  public static boolean booleano(Map<String, Object> objeto, String chave) {
    Object v = objeto == null ? null : objeto.get(chave);
    return v instanceof Boolean && (Boolean) v;
  }

  private Object lerValor() throws JsonInvalidoException {
    if (pos >= texto.length()) {
      throw new JsonInvalidoException("fim inesperado");
    }
    char c = texto.charAt(pos);
    switch (c) {
      case '{':
        return lerMapa();
      case '[':
        return lerVetor();
      case '"':
        return lerTexto();
      case 't':
        exigir("true");
        return Boolean.TRUE;
      case 'f':
        exigir("false");
        return Boolean.FALSE;
      case 'n':
        exigir("null");
        return null;
      default:
        return lerNumero();
    }
  }

  private Map<String, Object> lerMapa() throws JsonInvalidoException {
    entrar();
    Map<String, Object> mapa = new LinkedHashMap<>();
    pos++;
    pularBrancos();
    if (espiar() == '}') {
      pos++;
      sair();
      return mapa;
    }
    while (true) {
      pularBrancos();
      if (espiar() != '"') {
        throw new JsonInvalidoException("chave de objeto tem de ser texto, posicao " + pos);
      }
      String chave = lerTexto();
      pularBrancos();
      if (espiar() != ':') {
        throw new JsonInvalidoException("faltou ':' na posicao " + pos);
      }
      pos++;
      pularBrancos();
      mapa.put(chave, lerValor());
      pularBrancos();
      char c = espiar();
      if (c == ',') {
        pos++;
        continue;
      }
      if (c == '}') {
        pos++;
        sair();
        return mapa;
      }
      throw new JsonInvalidoException("esperado ',' ou '}' na posicao " + pos);
    }
  }

  private List<Object> lerVetor() throws JsonInvalidoException {
    entrar();
    List<Object> lista = new ArrayList<>();
    pos++;
    pularBrancos();
    if (espiar() == ']') {
      pos++;
      sair();
      return lista;
    }
    while (true) {
      pularBrancos();
      lista.add(lerValor());
      pularBrancos();
      char c = espiar();
      if (c == ',') {
        pos++;
        continue;
      }
      if (c == ']') {
        pos++;
        sair();
        return lista;
      }
      throw new JsonInvalidoException("esperado ',' ou ']' na posicao " + pos);
    }
  }

  private String lerTexto() throws JsonInvalidoException {
    pos++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= texto.length()) {
        throw new JsonInvalidoException("texto sem aspas de fechamento");
      }
      char c = texto.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        if (pos >= texto.length()) {
          throw new JsonInvalidoException("escape truncado");
        }
        char e = texto.charAt(pos++);
        switch (e) {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u':
            if (pos + 4 > texto.length()) {
              throw new JsonInvalidoException("escape unicode truncado");
            }
            String hex = texto.substring(pos, pos + 4);
            for (int i = 0; i < 4; i++) {
              if (Character.digit(hex.charAt(i), 16) < 0) {
                throw new JsonInvalidoException("escape unicode invalido: " + hex);
              }
            }
            sb.append((char) Integer.parseInt(hex, 16));
            pos += 4;
            break;
          default:
            throw new JsonInvalidoException("escape desconhecido: \\" + e);
        }
        continue;
      }
      // Caractere de controle cru dentro de texto e' proibido pelo RFC 8259.
      if (c < 0x20) {
        throw new JsonInvalidoException("caractere de controle cru no texto");
      }
      sb.append(c);
    }
  }

  private Double lerNumero() throws JsonInvalidoException {
    int inicio = pos;
    if (espiar() == '-') {
      pos++;
    }
    while (pos < texto.length() && "0123456789+-.eE".indexOf(texto.charAt(pos)) >= 0) {
      pos++;
    }
    String bruto = texto.substring(inicio, pos);
    if (bruto.isEmpty()) {
      throw new JsonInvalidoException("valor desconhecido na posicao " + inicio);
    }
    try {
      return Double.valueOf(bruto);
    } catch (NumberFormatException e) {
      throw new JsonInvalidoException("numero invalido: " + bruto);
    }
  }

  private void exigir(String literal) throws JsonInvalidoException {
    if (!texto.startsWith(literal, pos)) {
      throw new JsonInvalidoException("esperado '" + literal + "' na posicao " + pos);
    }
    pos += literal.length();
  }

  private char espiar() throws JsonInvalidoException {
    if (pos >= texto.length()) {
      throw new JsonInvalidoException("fim inesperado");
    }
    return texto.charAt(pos);
  }

  private void pularBrancos() {
    while (pos < texto.length()) {
      char c = texto.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pos++;
      } else {
        return;
      }
    }
  }

  private void entrar() throws JsonInvalidoException {
    if (++profundidade > PROFUNDIDADE_MAXIMA) {
      throw new JsonInvalidoException("aninhamento acima de " + PROFUNDIDADE_MAXIMA);
    }
  }

  private void sair() {
    profundidade--;
  }
}
