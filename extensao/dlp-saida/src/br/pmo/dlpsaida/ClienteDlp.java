package br.pmo.dlpsaida;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente HTTP do servico de DLP. Sem dependencia: {@link HttpURLConnection} e
 * um analisador de JSON minimo, suficiente para o formato que a API devolve.
 *
 * <p><b>POR QUE UM ANALISADOR PROPRIO E NAO UMA BIBLIOTECA:</b> este codigo roda
 * dentro do classpath do portal, ao lado de 567 jars. Acrescentar mais um so'
 * para ler seis campos de um JSON conhecido aumenta a chance de conflito de
 * versao -- que ja' e' a classe de defeito mais cara deste projeto. O formato
 * e' nosso dos dois lados, entao ler a mao e' seguro e nao envelhece.
 *
 * <p><b>FALHA ABERTA OU FECHADA:</b> se o servico de DLP nao responder, o
 * comportamento e' configuravel e o padrao e' FECHAR (negar a saida). Um DLP que
 * libera tudo quando cai e' um DLP que basta derrubar. Quem quiser priorizar
 * disponibilidade inverte em {@code exo.dlp.falhaAberta=true}, cientes do que
 * isso significa.
 */
public final class ClienteDlp {

  private final String base;
  private final String token;
  private final int tempoLimiteMs;

  /**
   * Le uma configuracao do AMBIENTE primeiro, e so' depois da propriedade.
   *
   * <p>O eXo NAO expande {@code ${env.X}} dentro de exo.properties -- medido em
   * 2026-08-31: a chave chegava com o literal {@code ${env.EXO_DLP_TOKEN:}} e o
   * servico devolvia 401. Alem disso, segredo em arquivo de propriedade e'
   * segredo em arquivo: o ambiente e' o lugar certo, e e' de onde o
   * docker-compose ja' o entrega.
   *
   * @param variavel nome da variavel de ambiente (tem precedencia)
   * @param propriedade nome da propriedade do portal (reserva)
   * @param padrao valor quando nenhum dos dois existe
   */
  public static String configuracao(String variavel, String propriedade,
                                    String padrao) {
    String v = System.getenv(variavel);
    if (v != null && !v.trim().isEmpty()) {
      return v.trim();
    }
    v = System.getProperty(propriedade);
    if (v != null && !v.trim().isEmpty() && !v.trim().startsWith("${")) {
      return v.trim();
    }
    return padrao;
  }

  public ClienteDlp(String base, String token, int tempoLimiteMs) {
    this.base = base != null && base.endsWith("/")
        ? base.substring(0, base.length() - 1) : base;
    this.token = token == null ? "" : token;
    this.tempoLimiteMs = tempoLimiteMs > 0 ? tempoLimiteMs : 10000;
  }

  /** Envia conteudo binario para analise. */
  public Veredito analisarArquivo(byte[] dados, Contexto ctx) throws IOException {
    StringBuilder corpo = new StringBuilder(512 + (dados == null ? 0 : dados.length * 4 / 3));
    corpo.append('{');
    ctx.escrever(corpo);
    if (dados != null) {
      corpo.append(",\"conteudo_base64\":\"")
           .append(Base64.getEncoder().encodeToString(dados)).append('"');
    }
    corpo.append('}');
    return enviar("/analisar", corpo.toString());
  }

  /** Envia texto (corpo de mensagem, area de transferencia, comentario). */
  public Veredito analisarTexto(String texto, Contexto ctx) throws IOException {
    StringBuilder corpo = new StringBuilder(512);
    corpo.append('{');
    ctx.escrever(corpo);
    corpo.append(",\"texto\":\"").append(escapar(texto)).append('"').append('}');
    return enviar("/analisar", corpo.toString());
  }

  /** Repassa uma chamada do console para a API, com o token. */
  public String repassar(String metodo, String caminho, String corpoJson)
      throws IOException {
    HttpURLConnection c = abrir(caminho, metodo);
    if (corpoJson != null && !corpoJson.isEmpty()) {
      c.setDoOutput(true);
      try (OutputStream os = c.getOutputStream()) {
        os.write(corpoJson.getBytes(StandardCharsets.UTF_8));
      }
    }
    return ler(c);
  }

  /**
   * Baixa um recurso BINARIO da API (conteudo retido em quarentena, CSV).
   *
   * <p>Separado de {@link #repassar} porque aquele decodifica como UTF-8: um
   * PDF ou um ZIP passando por String volta corrompido, e a corrupcao so'
   * apareceria quando alguem tentasse abrir o arquivo restaurado -- ou seja,
   * no pior momento possivel de uma investigacao.
   */
  public byte[] baixar(String caminho) throws IOException {
    HttpURLConnection c = abrir(caminho, "GET");
    int codigo = c.getResponseCode();
    InputStream is = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
    if (is == null) {
      throw new IOException("servico de DLP nao devolveu corpo em " + caminho);
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    is.close();
    if (codigo >= 400) {
      String texto = new String(bos.toByteArray(), StandardCharsets.UTF_8);
      throw new IOException("servico de DLP devolveu HTTP " + codigo + ": "
                            + (texto.length() > 300 ? texto.substring(0, 300) : texto));
    }
    return bos.toByteArray();
  }

  private Veredito enviar(String caminho, String corpoJson) throws IOException {
    HttpURLConnection c = abrir(caminho, "POST");
    c.setDoOutput(true);
    try (OutputStream os = c.getOutputStream()) {
      os.write(corpoJson.getBytes(StandardCharsets.UTF_8));
    }
    return Veredito.deJson(ler(c));
  }

  private HttpURLConnection abrir(String caminho, String metodo) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(base + caminho).openConnection();
    c.setRequestMethod(metodo);
    c.setConnectTimeout(tempoLimiteMs);
    c.setReadTimeout(tempoLimiteMs);
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    if (!token.isEmpty()) {
      c.setRequestProperty("X-DLP-Token", token);
    }
    return c;
  }

  private String ler(HttpURLConnection c) throws IOException {
    int codigo = c.getResponseCode();
    InputStream is = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
    if (is == null) {
      return "{}";
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    is.close();
    String texto = new String(bos.toByteArray(), StandardCharsets.UTF_8);
    if (codigo >= 400) {
      throw new IOException("servico de DLP devolveu HTTP " + codigo + ": "
                            + (texto.length() > 200 ? texto.substring(0, 200) : texto));
    }
    return texto;
  }

  static String escapar(String v) {
    if (v == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(v.length() + 16);
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
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
    return b.toString();
  }

  /** Dados da tentativa de saida. */
  public static final class Contexto {
    public String canal = "DOWNLOAD";
    public String usuario = "";
    /**
     * E-mail de quem esta' tentando a saida, resolvido no portal.
     *
     * <p>O servico de DLP NAO tem cadastro de pessoas e nao pode inventar
     * endereco. Sem este campo, a acao NOTIFICAR_USUARIO nao tinha para onde
     * enviar e o aviso morria como "sem destinatario" -- a acao existia e nao
     * chegava a ninguem, que e' a definicao de encenacao.
     */
    public String email = "";
    public String ip = "";
    public String destino = "";
    public String nomeArquivo = "";
    public String recurso = "";
    /**
     * MODO OBSERVACAO: o servico registra o incidente e NAO age.
     *
     * <p>Sem este campo o portal ficava em observacao e o SERVICO agia mesmo
     * assim -- retinha copia no cofre e mandava e-mail ao administrador.
     * Medido em producao em 2026-09-01: "nada muda para o usuario" nao era
     * verdade, e o cofre enchia durante a fase em que a politica ainda estava
     * sendo dimensionada.
     */
    public boolean observacao;
    public String[] grupos = new String[0];

    void escrever(StringBuilder b) {
      b.append("\"canal\":\"").append(escapar(canal)).append('"');
      b.append(",\"usuario\":\"").append(escapar(usuario)).append('"');
      b.append(",\"email\":\"").append(escapar(email)).append('"');
      b.append(",\"ip\":\"").append(escapar(ip)).append('"');
      b.append(",\"destino\":\"").append(escapar(destino)).append('"');
      b.append(",\"nome_arquivo\":\"").append(escapar(nomeArquivo)).append('"');
      b.append(",\"recurso\":\"").append(escapar(recurso)).append('"');
      b.append(",\"observacao\":").append(observacao);
      b.append(",\"grupos\":[");
      for (int i = 0; i < grupos.length; i++) {
        if (i > 0) {
          b.append(',');
        }
        b.append('"').append(escapar(grupos[i])).append('"');
      }
      b.append(']');
    }
  }

  /**
   * Resposta do servico.
   *
   * <p><b>O QUE ESTA CLASSE PASSOU A LER, E POR QUE IMPORTA.</b> Ate'
   * 2026-08-31 ela lia cinco campos escalares: {@code permitido},
   * {@code mensagem}, {@code regra_nome}, {@code severidade} e
   * {@code incidente}. <b>Nao recebia a lista de acoes.</b> A consequencia
   * estava documentada em {@code dlp/PENDENCIAS.md}: o portal era incapaz de
   * honrar qualquer acao alem de permitir ou bloquear. O servico devolvia
   * {@code texto_mascarado} e o filtro simplesmente nao o usava -- no download
   * o conteudo passava inteiro ou era barrado, <b>nunca saia mascarado</b>,
   * ainda que o incidente registrasse "MASCARAR" como se tivesse acontecido.
   *
   * <p>Agora vem tambem o CONTEUDO TRANSFORMADO (mascarado ou cifrado), o tipo
   * e o nome de saida, a orientacao ao usuario e o numero do item retido em
   * quarentena. E' o que permite ao filtro entregar a versao segura em vez de
   * escolher entre tudo ou nada.
   */
  public static final class Veredito {
    public boolean permitido = true;
    public String mensagem = "";
    public String regraNome = "";
    public String severidade = "NENHUMA";
    public String incidente = "";
    /** O que o executor do servico REALMENTE fez. */
    public String[] acoesExecutadas = new String[0];
    /** Acao que a regra pediu e nao foi possivel cumprir. */
    public String[] acoesNaoAplicaveis = new String[0];
    /** Conteudo a entregar no lugar do original; nulo quando nada mudou. */
    public byte[] conteudo;
    public String mimeSaida = "";
    public String nomeSaida = "";
    public String orientacao = "";
    public String quarentena = "";

    public boolean temTransformacao() {
      return conteudo != null && conteudo.length > 0;
    }

    public boolean fez(String acao) {
      for (String a : acoesExecutadas) {
        if (a.equals(acao)) {
          return true;
        }
      }
      return false;
    }

    public static Veredito deJson(String json) {
      Veredito v = new Veredito();
      v.permitido = !"false".equals(campo(json, "permitido"));
      v.mensagem = textoDe(json, "mensagem");
      v.regraNome = textoDe(json, "regra_nome");
      v.severidade = textoDe(json, "severidade");
      v.incidente = textoDe(json, "incidente");
      v.acoesExecutadas = listaDe(json, "acoes_executadas");
      v.acoesNaoAplicaveis = listaDe(json, "acoes_nao_aplicaveis");
      v.orientacao = textoDe(json, "orientacao");
      v.quarentena = textoDe(json, "quarentena");
      v.mimeSaida = textoDe(json, "mime_saida");
      v.nomeSaida = textoDe(json, "nome_saida");
      String base64 = textoDe(json, "conteudo_base64");
      if (!base64.isEmpty()) {
        try {
          v.conteudo = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
          // Base64 corrompido nao pode virar "entrega o original": seria
          // deixar passar em claro justamente o que a regra mandou
          // transformar. Sem conteudo, o filtro trata como falha e nega.
          v.conteudo = new byte[0];
          v.permitido = false;
          v.mensagem = "resposta do servico de DLP ilegivel; transferencia negada";
        }
      }
      return v;
    }

    /**
     * Le um vetor de textos simples do JSON.
     *
     * <p>Aceita tambem vetor de OBJETOS ({@code acoes_nao_aplicaveis} vem como
     * {@code [{"acao":...,"motivo":...}]}) e, nesse caso, devolve os pares
     * como texto -- e' o bastante para registrar no log, que e' o unico uso.
     */
    static String[] listaDe(String json, String nome) {
      String alvo = "\"" + nome + "\"";
      int i = json.indexOf(alvo);
      if (i < 0) {
        return new String[0];
      }
      int abre = json.indexOf('[', i + alvo.length());
      if (abre < 0) {
        return new String[0];
      }
      java.util.List<String> itens = new java.util.ArrayList<>();
      StringBuilder atual = new StringBuilder();
      boolean dentroDeTexto = false;
      int profundidade = 0;
      for (int p = abre + 1; p < json.length(); p++) {
        char c = json.charAt(p);
        if (dentroDeTexto) {
          if (c == '\\' && p + 1 < json.length()) {
            atual.append(json.charAt(++p));
          } else if (c == '"') {
            dentroDeTexto = false;
            if (profundidade == 0) {
              itens.add(atual.toString());
              atual.setLength(0);
            } else {
              atual.append(c);
            }
          } else {
            atual.append(c);
          }
          continue;
        }
        if (c == '"') {
          dentroDeTexto = true;
          if (profundidade > 0) {
            atual.append(c);
          }
        } else if (c == '{' || c == '[') {
          profundidade++;
          atual.append(c);
        } else if (c == '}') {
          profundidade--;
          atual.append(c);
          if (profundidade == 0) {
            itens.add(atual.toString());
            atual.setLength(0);
          }
        } else if (c == ']') {
          if (profundidade == 0) {
            break;
          }
          profundidade--;
          atual.append(c);
        } else if (profundidade > 0) {
          atual.append(c);
        }
      }
      return itens.toArray(new String[0]);
    }

    /** Le um campo escalar do JSON. Formato conhecido, dos dois lados nosso. */
    static String campo(String json, String nome) {
      String alvo = "\"" + nome + "\"";
      int i = json.indexOf(alvo);
      if (i < 0) {
        return null;
      }
      int j = json.indexOf(':', i + alvo.length());
      if (j < 0) {
        return null;
      }
      int k = j + 1;
      while (k < json.length() && Character.isWhitespace(json.charAt(k))) {
        k++;
      }
      if (k >= json.length()) {
        return null;
      }
      if (json.charAt(k) == '"') {
        StringBuilder b = new StringBuilder();
        for (int p = k + 1; p < json.length(); p++) {
          char c = json.charAt(p);
          if (c == '\\' && p + 1 < json.length()) {
            char s = json.charAt(++p);
            switch (s) {
              case 'n': b.append('\n'); break;
              case 'r': b.append('\r'); break;
              case 't': b.append('\t'); break;
              case 'u':
                if (p + 4 < json.length()) {
                  b.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                  p += 4;
                }
                break;
              default: b.append(s);
            }
          } else if (c == '"') {
            break;
          } else {
            b.append(c);
          }
        }
        return b.toString();
      }
      int fim = k;
      while (fim < json.length() && ",}] \n\r\t".indexOf(json.charAt(fim)) < 0) {
        fim++;
      }
      return json.substring(k, fim);
    }

    /**
     * Le um campo de texto do JSON.
     *
     * <p>Publico porque o console (noutro pacote) precisa ler o nome do
     * arquivo retido para o navegador salvar com um nome util. Sem isso, o
     * download da quarentena viria com o identificador do cofre como nome, e
     * o analista teria de renomear a mao todo arquivo que restaura.
     */
    public static String textoDe(String json, String nome) {
      String v = campo(json, nome);
      return v == null || "null".equals(v) ? "" : v;
    }
  }
}
