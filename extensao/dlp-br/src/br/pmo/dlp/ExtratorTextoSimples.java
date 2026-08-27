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

  /**
   * Acima desta proporcao de code points implausiveis, o conteudo decodificado
   * nao e' texto. Em porcentagem.
   *
   * <p>Cinco por cento nao e' chute: e' medida. Binario aleatorio decodificado
   * como UTF-16 da' 14%, e como ISO-8859-1 da' 23%; documento com acentuacao
   * portuguesa, texto CJK e texto com emoji dao' 0%. O corte fica no meio de uma
   * separacao de tres vezes, e nao encostado em nenhum dos lados.
   *
   * <p>A primeira versao deste criterio contava caracteres de CONTROLE e cortava
   * em 2%. Media: binario aleatorio em UTF-16 da' exatamente 2% de controle --
   * ou seja, o criterio era cara-ou-coroa, e so' pegou o caso da prova por
   * sorte. Contar o que NAO E' CARACTERE ATRIBUIDO separa cinco vezes melhor.
   */
  private static final int TETO_IMPLAUSIVEL_POR_CENTO = 5;

  /** Abaixo deste tamanho, a proporcao e' ruido e nao se aplica. */
  private static final int MINIMO_PARA_PROPORCAO = 64;

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

    // PONTO UNICO DE CONFERENCIA, e a razao de ele existir esta' em
    // recusarSeNaoForTexto: pareceBinario roda sobre os BYTES e tem um ramo que
    // o desvia (arquivo com BOM). Conferir de novo aqui, DEPOIS de todos os
    // ramos de decodificacao convergirem, fecha o desvio por construcao em vez
    // de exigir que cada ramo novo lembre de repetir a checagem.
    recusarSeNaoForTexto(texto, nomeArquivo);
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
    // O BUFFER INTEIRO, nao so' o comeco. Varrer apenas os primeiros 8 KiB e' a
    // receita copiada de editor de texto, e num DLP ela e' um convite: basta
    // por 8 KiB de texto na frente do que se quer esconder. O custo de varrer
    // tudo e' uma passada por, no maximo, o teto de bytes -- irrelevante ao
    // lado da varredura por regex que vem depois.
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Confere, JA' DECODIFICADO, se o resultado e' mesmo texto.
   *
   * <p><b>POR QUE ISTO EXISTE, ALEM DE {@link #pareceBinario}.</b> Aquele roda
   * sobre os bytes e tem um ramo que o desvia: arquivo com BOM e' declarado
   * "nao binario" sem olhar mais nada, porque UTF-16 legitimamente contem
   * {@code NUL}. So' que basta prefixar {@code FF FE} a um binario qualquer
   * para tomar esse desvio -- e o binario seria decodificado como UTF-16,
   * viraria lixo, nao casaria com regra nenhuma e sairia do motor como
   * <b>documento limpo</b>.
   *
   * <p>E' o mesmo defeito que a sessao projetos-97 encontrou no proprio codigo
   * dela em 2026-08-27 ("correcao esquecida num ramo e' correcao nao feita") e
   * que me fez auditar este arquivo. A licao que fica no desenho: a conferencia
   * nao vai em cada ramo, vai onde os ramos CONVERGEM. Ramo novo passa a herdar
   * a checagem em vez de precisar lembrar dela.
   *
   * <p>Dois criterios:
   * <ol>
   *   <li>qualquer {@code U+0000} reprova. Texto de documento nao tem NUL --
   *       nem em UTF-16, porque ali o NUL fica nos BYTES, nunca no caractere
   *       decodificado;</li>
   *   <li>mais de {@value #TETO_IMPLAUSIVEL_POR_CENTO}% de code points
   *       IMPLAUSIVEIS reprova: nao atribuido, uso privado, substituto solto,
   *       caractere de controle ou {@code U+FFFD}. Binario lido como texto
   *       produz isso em abundancia (medido: 14% em UTF-16, 23% em Latin-1);
   *       documento de verdade nao produz nenhum.</li>
   * </ol>
   *
   * <p><b>A contagem e' por CODE POINT, nao por {@code char}, e isso decide um
   * falso positivo inteiro.</b> Emoji e todo o plano suplementar sao gravados em
   * Java como PAR de substitutos, e cada metade do par, olhada sozinha, tem tipo
   * {@code SURROGATE} -- que e' justamente uma das marcas de lixo. Percorrer por
   * {@code char} reprovaria um documento com emoji como se fosse binario.
   * Percorrendo por code point, o par vira um caractere definido e o substituto
   * SOLTO (que e' de fato sinal de lixo) continua sendo contado. Medido: texto
   * com emoji da' 0%.
   *
   * <p>O criterio de proporcao so' vale a partir de
   * {@value #MINIMO_PARA_PROPORCAO} caracteres: em texto curtissimo um unico
   * caractere estranho estoura qualquer percentual.
   */
  private static void recusarSeNaoForTexto(String texto, String nomeArquivo)
      throws ExtracaoIndisponivelException {
    if (texto.isEmpty()) {
      return;
    }
    int implausiveis = 0;
    int total = 0;
    for (int i = 0; i < texto.length(); ) {
      int ponto = texto.codePointAt(i);
      i += Character.charCount(ponto);
      total++;
      if (ponto == 0) {
        throw new ExtracaoIndisponivelException(
            "conteudo binario disfarcado de texto (caractere nulo apos decodificar"
            + (nomeArquivo == null ? "" : " " + nomeArquivo) + ")");
      }
      if (implausivel(ponto)) {
        implausiveis++;
      }
    }
    if (total >= MINIMO_PARA_PROPORCAO
        && (implausiveis * 100L) / total > TETO_IMPLAUSIVEL_POR_CENTO) {
      throw new ExtracaoIndisponivelException(
          "conteudo nao parece texto: " + implausiveis + " de " + total
          + " caracteres nao sao caracteres de escrita"
          + (nomeArquivo == null ? "" : " em " + nomeArquivo)
          + ". NAO pode ser considerado livre de dados sensiveis.");
    }
  }

  /** Code point que nao aparece em documento escrito por gente. */
  private static boolean implausivel(int ponto) {
    if (ponto == 0xFFFD || !Character.isDefined(ponto)) {
      return true;
    }
    int tipo = Character.getType(ponto);
    if (tipo == Character.UNASSIGNED || tipo == Character.PRIVATE_USE
        || tipo == Character.SURROGATE) {
      return true;
    }
    // Tabulacao, nova linha, retorno e avanco de pagina sao controle E sao
    // texto legitimo -- estao em praticamente todo arquivo real.
    return tipo == Character.CONTROL
           && ponto != '\t' && ponto != '\n' && ponto != '\r' && ponto != '\f';
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
   * Tira as ETIQUETAS de XML/HTML e preserva TODO o texto, inclusive o que
   * estava dentro de comentario, de {@code <script>} e de {@code <style>}.
   *
   * <p>Cada etiqueta vira um espaco, nunca some sem deixar separador. Colar
   * {@code <td>123.456.789-01</td><td>2</td>} sem separador produziria
   * {@code 123.456.789-012}, que NAO e' CPF valido: a mascara silenciosamente
   * deixaria de proteger uma tabela HTML inteira.
   *
   * <p><b>POR QUE O CONTEUDO DE COMENTARIO E DE SCRIPT NAO E' MAIS DESCARTADO.</b>
   * Ate' 2026-08-27 este metodo derrubava os tres com o conteudo dentro, sob o
   * argumento de que "codigo nao e' texto do documento". O argumento esta'
   * errado justamente para um DLP, por dois motivos:
   *
   * <ol>
   *   <li>comentario de HTML e' <b>o esconderijo classico</b>: o dado esta' no
   *       arquivo, nao aparece no navegador, e sai inteiro para quem abrir a
   *       fonte. Descarta-lo antes de varrer e' varrer justamente onde nao
   *       esta';</li>
   *   <li>a regra {@code SEGREDO_EM_TEXTO_CLARO} deste mesmo pacote procura
   *       {@code senha=}, {@code api_key=} e {@code token=} -- que vivem
   *       precisamente dentro de {@code <script>} e de bloco de configuracao.
   *       Descartar script era <b>desligar uma regra propria</b> sem que nada
   *       acusasse.</li>
   * </ol>
   *
   * <p>O preco e' mais ruido vindo de codigo. E' um preco baixo: toda regra de
   * severidade ALTA confere digito verificador, entao ruido de JavaScript nao
   * vira quarentena -- no maximo vira contagem em rotulo de severidade baixa.
   */
  static String removerMarcacao(String texto) {
    String semCdata = texto.replaceAll("(?s)<!\\[CDATA\\[(.*?)\\]\\]>", " $1 ");
    // So' as CERCAS do comentario viram espaco; o miolo continua e sera' varrido.
    String semCercas = semCdata.replace("<!--", " ").replace("-->", " ");
    String semEtiquetas = semCercas.replaceAll("(?s)<[^>]*>", " ");
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
