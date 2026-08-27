package br.pmo.dlp.portal;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Locale;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.SecureContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.xml.sax.SAXException;

import br.pmo.dlp.Extrator;
import br.pmo.dlp.ExtratorTextoSimples;
import br.pmo.dlp.Varredura;

/**
 * Extrator de texto que roda DENTRO do portal, sobre o Tika, o PDFBox e o POI
 * que a plataforma ja' embarca (Tika 1.28.4 em {@code /opt/exo/lib}).
 *
 * <p>E' o par do {@link ExtratorTextoSimples}: aquele trata o que ja' e' texto e
 * compila fora do container; este trata PDF, .docx, .xlsx, .pptx, .odt e .rtf --
 * que sao a maior parte de um acervo de orgao publico -- e so' compila com os
 * jars da plataforma.
 *
 * <h2>As tres decisoes que nao sao detalhe</h2>
 *
 * <b>1. O tipo declarado pelo cliente e' ignorado.</b> Quem envia o arquivo diz
 * o {@code Content-Type}, e quem quer escapar de um filtro por tipo mente. O
 * tipo real sai do {@link Detector} do Tika, que olha os bytes. O nome do
 * arquivo entra so' como dica.
 *
 * <b>2. Documento SEM TEXTO EXTRAIVEL levanta excecao, nao devolve "".</b> Um
 * PDF digitalizado -- imagem de papel, sem camada de texto -- e' o formato mais
 * comum de documento sensivel numa prefeitura, e devolve string vazia em
 * qualquer extrator. String vazia entra no motor e sai como "documento limpo":
 * a digitalizacao de uma ficha funcional inteira seria classificada como
 * PUBLICO. Aqui isso vira {@link ExtracaoIndisponivelException}, e a politica
 * trata como suspeito. E' exatamente o caso que o OCR resolveria -- e que, sem
 * OCR, ao menos NAO passa despercebido.
 *
 * <b>3. Tetos contra bomba de descompressao.</b> Um .docx de 40 KB pode conter
 * 4 GB descomprimidos; um PDF pode aninhar anexos sem fim. Sem teto, um upload
 * de quarenta quilobytes derruba a JVM do portal para todo mundo -- negacao de
 * servico ao alcance de quem so' tem permissao de anexar arquivo. Por isso
 * {@link SecureContentHandler} (razao de compressao e profundidade) mais um
 * teto de caracteres escritos.
 *
 * <h2>Como a truncagem chega ao motor</h2>
 * O teto de escrita e' deliberadamente <b>um caractere maior</b> que
 * {@link Varredura#TETO_CARACTERES_PADRAO}. Assim, se o Tika truncar, o texto
 * devolvido ja' passa do teto do motor, e o motor marca
 * {@code completa=false} sozinho -- sem inventar um segundo canal de sinalizacao
 * que alguem esqueceria de olhar.
 */
public final class ExtratorDoPortal implements Extrator {

  /**
   * Razao maxima entre bytes lidos e bytes de entrada. Cem: um .docx comprime
   * bem, mas cem para um ja' e' arquivo construido para explodir, nao documento.
   */
  private static final long RAZAO_MAXIMA_COMPRESSAO = 100L;

  /** Profundidade maxima de aninhamento (anexo dentro de anexo). */
  private static final int PROFUNDIDADE_MAXIMA = 10;

  /** Tipos que nao tem texto por natureza. Precisam de OCR ou nao interessam. */
  private static final String[] SEM_TEXTO = { "image/", "audio/", "video/" };

  private final int tetoCaracteres;

  private final TikaConfig configuracao;

  private final ExtratorTextoSimples simples;

  public ExtratorDoPortal() {
    this(Varredura.TETO_CARACTERES_PADRAO + 1, TikaConfig.getDefaultConfig());
  }

  public ExtratorDoPortal(int tetoCaracteres, TikaConfig configuracao) {
    if (tetoCaracteres <= 0) {
      throw new IllegalArgumentException("teto de caracteres tem de ser positivo");
    }
    this.tetoCaracteres = tetoCaracteres;
    this.configuracao = configuracao;
    this.simples = new ExtratorTextoSimples();
  }

  /**
   * Aceita tudo o que NAO e' imagem, audio ou video pelo nome/tipo declarado --
   * a decisao real e' tomada em {@link #extrair}, depois de olhar os bytes.
   * Recusar aqui pelo nome seria confiar em quem envia.
   */
  @Override
  public boolean aceita(String nomeArquivo, String tipoMime) {
    if (tipoMime == null) {
      return true;
    }
    String mime = tipoMime.toLowerCase(Locale.ROOT).trim();
    for (String prefixo : SEM_TEXTO) {
      if (mime.startsWith(prefixo)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String extrair(InputStream entrada, String nomeArquivo, String tipoMime)
      throws ExtracaoIndisponivelException, IOException {
    if (entrada == null) {
      throw new ExtracaoIndisponivelException("fluxo nulo");
    }

    // TikaInputStream permite ao SecureContentHandler medir a razao de
    // compressao: ele precisa saber quantos bytes ja' foram lidos da ORIGEM.
    try (TikaInputStream fluxo = TikaInputStream.get(entrada)) {

      Metadata metadados = new Metadata();
      if (nomeArquivo != null && !nomeArquivo.isEmpty()) {
        metadados.set(TikaCoreProperties.RESOURCE_NAME_KEY, nomeArquivo);
      }

      MediaType tipoReal = detectar(fluxo, metadados);
      recusarSeNaoTemTexto(tipoReal, nomeArquivo);

      // Formato que ja' e' texto: o extrator puro faz melhor -- ele conhece
      // ISO-8859-1 do legado brasileiro, e o Tika chutaria a codificacao.
      if (simples.aceita(nomeArquivo, tipoReal.toString())) {
        return simples.extrair(fluxo, nomeArquivo, tipoReal.toString());
      }

      String texto = extrairComTika(fluxo, metadados, nomeArquivo);

      if (texto.trim().isEmpty()) {
        throw new ExtracaoIndisponivelException(
            "documento de tipo " + tipoReal + " sem nenhum texto extraivel"
            + (nomeArquivo == null ? "" : " (" + nomeArquivo + ")")
            + ": provavelmente e' digitalizacao de papel, e exige OCR."
            + " NAO pode ser considerado livre de dados sensiveis.");
      }
      return texto;
    }
  }

  // ===========================================================================

  private MediaType detectar(TikaInputStream fluxo, Metadata metadados) throws IOException {
    Detector detector = configuracao.getDetector();
    return detector.detect(fluxo, metadados);
  }

  private void recusarSeNaoTemTexto(MediaType tipo, String nomeArquivo)
      throws ExtracaoIndisponivelException {
    String mime = tipo.toString().toLowerCase(Locale.ROOT);
    for (String prefixo : SEM_TEXTO) {
      if (mime.startsWith(prefixo)) {
        throw new ExtracaoIndisponivelException(
            "arquivo de tipo " + tipo + (nomeArquivo == null ? "" : " (" + nomeArquivo + ")")
            + " nao tem texto para varrer. Exige inspecao OCR, que esta' instalacao"
            + " ainda nao tem. NAO pode ser considerado livre de dados sensiveis.");
      }
    }
  }

  /**
   * O parse propriamente dito, com os dois tetos armados.
   *
   * <p>A truncagem por teto de escrita NAO e' erro: o Tika sinaliza com
   * {@code SAXException}, e {@link WriteOutContentHandler#isWriteLimitReached}
   * distingue esse caso de uma falha de verdade. Quando e' truncagem, o texto
   * escrito ate' ali e' devolvido -- e, por ser maior que o teto do motor, faz o
   * motor marcar a varredura como incompleta.
   */
  private String extrairComTika(TikaInputStream fluxo, Metadata metadados, String nomeArquivo)
      throws ExtracaoIndisponivelException, IOException {

    StringWriter escritor = new StringWriter();
    WriteOutContentHandler limitador = new WriteOutContentHandler(escritor, tetoCaracteres);
    SecureContentHandler seguro = new SecureContentHandler(limitador, fluxo);
    seguro.setMaximumCompressionRatio(RAZAO_MAXIMA_COMPRESSAO);
    seguro.setMaximumDepth(PROFUNDIDADE_MAXIMA);

    Parser analisador = new AutoDetectParser(configuracao);
    ParseContext contexto = new ParseContext();
    // Anexo dentro de documento TEM de ser varrido: esconder a planilha de CPFs
    // dentro de um .docx seria o truque obvio. O custo disso e' o risco de
    // bomba, ja' coberto pelo SecureContentHandler acima.
    contexto.set(Parser.class, analisador);

    try {
      analisador.parse(fluxo, seguro, metadados, contexto);
    } catch (SAXException e) {
      if (!limitador.isWriteLimitReached(e)) {
        try {
          seguro.throwIfCauseOf(e);
        } catch (TikaException bomba) {
          throw new ExtracaoIndisponivelException(
              "arquivo recusado por limite de seguranca (possivel bomba de"
              + " descompressao)" + (nomeArquivo == null ? "" : " em " + nomeArquivo)
              + ": " + bomba.getMessage(), bomba);
        }
        throw new ExtracaoIndisponivelException(
            "falha ao ler o conteudo"
            + (nomeArquivo == null ? "" : " de " + nomeArquivo) + ": " + e.getMessage(), e);
      }
      // Truncagem: segue com o que foi escrito.
    } catch (TikaException e) {
      throw new ExtracaoIndisponivelException(
          "formato nao suportado ou arquivo corrompido/cifrado"
          + (nomeArquivo == null ? "" : " em " + nomeArquivo) + ": " + e.getMessage(), e);
    }

    return escritor.toString();
  }
}
