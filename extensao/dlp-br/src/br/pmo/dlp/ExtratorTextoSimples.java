package br.pmo.dlp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Extrator de formatos que sao texto por natureza: {@code .txt}, {@code .csv},
 * {@code .json}, {@code .xml}, {@code .html}, {@code .md}, {@code .log},
 * {@code .properties}, {@code .sql}, {@code .yaml}.
 *
 * <p>Puro JDK. Nao trata PDF, .docx nem imagem -- esses tem extrator proprio
 * dentro do portal, onde Tika, PDFBox e POI existem.
 *
 * <h2>Deteccao de codificacao, e por que ela nao e' detalhe no Brasil</h2>
 * Acervo de orgao publico esta' cheio de CSV exportado de sistema legado em
 * ISO-8859-1 (Latin-1). Ler esse arquivo como UTF-8 nao explode: o decodificador
 * permissivo troca cada byte invalido por {@code U+FFFD}. O texto fica com
 * simbolos estranhos e -- o que importa aqui -- <b>a regex de CPF continua
 * casando, porque digito e' ASCII</b>. Entao por que se preocupar? Porque a
 * troca desloca posicoes e corrompe o mascaramento: o trecho substituido deixa
 * de ser o trecho detectado. Por isso a ordem e':
 *
 * <ol>
 *   <li>BOM, se houver -- e' declaracao explicita, vale mais que palpite;</li>
 *   <li>UTF-8 ESTRITO. Estrito de proposito: qualquer byte invalido derruba a
 *       tentativa, em vez de mascarar o problema com {@code U+FFFD};</li>
 *   <li>ISO-8859-1, que nunca falha (todo byte tem correspondente) e e' a
 *       aposta certa para o legado brasileiro.</li>
 * </ol>
 *
 * <h2>Teto de bytes</h2>
 * O fluxo e' lido ate' {@link #getTetoBytes()} e o resto e' DESCARTADO -- mas
 * quem chama fica sabendo, porque o texto devolvido vem menor que o arquivo e o
 * {@link Varredura} marca {@code completa=false}. Sem teto, um upload de 2 GB
 * viraria 2 GB de {@code byte[]} mais 4 GB de {@code String} dentro do heap do
 * portal.
 */
public final class ExtratorTextoSimples implements Extrator {

  /**
   * Teto padrao: 16 MiB de bytes lidos. Casado com o teto de caracteres de
   * {@link Varredura#TETO_CARACTERES_PADRAO} (2 milhoes) com folga -- 16 MiB de
   * UTF-8 brasileiro dao' de 8 a 16 milhoes de caracteres, entao o corte
   * efetivo vem do motor, e nao daqui, para todo arquivo de texto real.
   */
  public static final int TETO_BYTES_PADRAO = 16 * 1024 * 1024;

  private static final List<String> EXTENSOES = Arrays.asList(
      "txt", "csv", "tsv", "json", "xml", "html", "htm", "xhtml", "md",
      "log", "properties", "conf", "ini", "sql", "yaml", "yml", "srt", "vcf");

  /** Extensoes cujo conteudo e' marcacao e precisa perder as etiquetas. */
  private static final List<String> COM_MARCACAO = Arrays.asList(
      "xml", "html", "htm", "xhtml");

  private final int tetoBytes;

  public ExtratorTextoSimples() {
    this(TETO_BYTES_PADRAO);
  }

  public ExtratorTextoSimples(int tetoBytes) {
    if (tetoBytes <= 0) {
      throw new IllegalArgumentException("teto de bytes tem de ser positivo");
    }
    this.tetoBytes = tetoBytes;
  }

  public int getTetoBytes() {
    return tetoBytes;
  }

  @Override
  public boolean aceita(String nomeArquivo, String tipoMime) {
    String extensao = extensaoDe(nomeArquivo);
    if (extensao != null && EXTENSOES.contains(extensao)) {
      return true;
    }
    if (tipoMime == null) {
      return false;
    }
    String mime = tipoMime.toLowerCase(Locale.ROOT).trim();
    return mime.startsWith("text/")
           || mime.equals("application/json")
           || mime.equals("application/xml")
           || mime.equals("application/xhtml+xml")
           || mime.equals("application/sql");
  }

  @Override
  public String extrair(InputStream entrada, String nomeArquivo, String tipoMime)
      throws ExtracaoIndisponivelException, IOException {
    if (entrada == null) {
      throw new ExtracaoIndisponivelException("fluxo nulo");
    }
    if (!aceita(nomeArquivo, tipoMime)) {
      throw new ExtracaoIndisponivelException(
          "formato nao tratado por este extrator: nome=" + nomeArquivo + " mime=" + tipoMime);
    }

    byte[] bytes = lerAte(entrada, tetoBytes);
    if (bytes.length == 0) {
      return "";
    }
    if (pareceBinario(bytes)) {
      // Extensao mente. Deixar passar produziria texto lixo, e regex sobre
      // lixo binario gera falso positivo puro -- que e' o que mata a
      // credibilidade de um DLP.
      throw new ExtracaoIndisponivelException(
          "conteudo binario com extensao de texto (nome=" + nomeArquivo + ")");
    }

    String texto = decodificar(bytes);
    String extensao = extensaoDe(nomeArquivo);
    if (extensao != null && COM_MARCACAO.contains(extensao)) {
      texto = removerMarcacao(texto);
    }
    return texto;
  }

  // ===========================================================================
  // Leitura
  // ===========================================================================

  private static byte[] lerAte(InputStream entrada, int teto) throws IOException {
    ByteArrayOutputStream saida = new ByteArrayOutputStream(Math.min(teto, 64 * 1024));
    byte[] balde = new byte[8192];
    int total = 0;
    int lidos;
    while (total < teto && (lidos = entrada.read(balde, 0, Math.min(balde.length, teto - total))) != -1) {
      saida.write(balde, 0, lidos);
      total += lidos;
    }
    return saida.toByteArray();
  }

  /**
   * Heuristica de binario: byte NUL nos primeiros 8 KiB. Nenhuma codificacao de
   * texto usada em documento administrativo produz NUL no meio -- UTF-16 sim,
   * mas UTF-16 chega com BOM e e' tratado antes desta checagem.
   */
  private static boolean pareceBinario(byte[] bytes) {
    if (temBom(bytes) != null) {
      return false;
    }
    int limite = Math.min(bytes.length, 8192);
    for (int i = 0; i < limite; i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  // ===========================================================================
  // Codificacao
  // ===========================================================================

  private static Charset temBom(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
      return StandardCharsets.UTF_8;
    }
    if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
      return StandardCharsets.UTF_16LE;
    }
    if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
      return StandardCharsets.UTF_16BE;
    }
    return null;
  }

  static String decodificar(byte[] bytes) {
    Charset comBom = temBom(bytes);
    if (comBom != null) {
      int salto = comBom == StandardCharsets.UTF_8 ? 3 : 2;
      return new String(bytes, salto, bytes.length - salto, comBom);
    }
    String utf8 = tentarEstrito(bytes, StandardCharsets.UTF_8);
    if (utf8 != null) {
      return utf8;
    }
    // ISO-8859-1 nunca falha: os 256 bytes tem correspondente.
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  /** Devolve nulo -- e nao texto com U+FFFD -- quando a codificacao nao serve. */
  private static String tentarEstrito(byte[] bytes, Charset charset) {
    CharsetDecoder decodificador = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      CharBuffer buffer = decodificador.decode(ByteBuffer.wrap(bytes));
      return buffer.toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  // ===========================================================================
  // Marcacao
  // ===========================================================================

  /**
   * Tira etiquetas de XML/HTML preservando o TAMANHO do texto restante em
   * relacao ao conteudo -- cada etiqueta vira um espaco, nunca some sem deixar
   * separador. Colar {@code <td>123.456.789-01</td><td>2</td>} sem separador
   * produziria {@code 123.456.789-012}, que NAO e' CPF valido: a mascara
   * silenciosamente deixaria de proteger uma tabela HTML inteira.
   *
   * <p>Tambem derruba o conteudo de {@code <script>} e {@code <style>}, que sao
   * codigo e nao texto do documento.
   */
  static String removerMarcacao(String texto) {
    String semScript = texto.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                            .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                            .replaceAll("(?s)<!--.*?-->", " ")
                            .replaceAll("(?s)<!\\[CDATA\\[(.*?)\\]\\]>", "$1");
    String semEtiquetas = semScript.replaceAll("(?s)<[^>]*>", " ");
    return desescapar(semEtiquetas);
  }

  /** So' as cinco entidades obrigatorias de XML mais o espaco duro. */
  private static String desescapar(String texto) {
    if (texto.indexOf('&') < 0) {
      return texto;
    }
    return texto.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
  }

  static String extensaoDe(String nomeArquivo) {
    if (nomeArquivo == null) {
      return null;
    }
    int ponto = nomeArquivo.lastIndexOf('.');
    if (ponto < 0 || ponto == nomeArquivo.length() - 1) {
      return null;
    }
    return nomeArquivo.substring(ponto + 1).toLowerCase(Locale.ROOT);
  }
}
