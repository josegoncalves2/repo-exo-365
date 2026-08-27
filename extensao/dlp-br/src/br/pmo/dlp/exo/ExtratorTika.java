package br.pmo.dlp.exo;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import br.pmo.dlp.Extrator;

/**
 * Extracao de texto por Apache Tika: PDF, DOCX, XLSX, PPTX, ODT, RTF, HTML.
 *
 * <p><b>POR QUE TIKA, E POR QUE SEM DEPENDENCIA NOVA.</b> O dado sensivel de
 * uma prefeitura quase nunca esta' num .txt: esta' num oficio em PDF, numa
 * planilha de folha em XLSX, num memorando em DOCX. Um DLP que so' le texto
 * puro cobre a minoria dos casos e da' uma falsa sensacao de protecao.
 *
 * <p>Tika 1.28.4, PDFBox e POI JA' ESTAO em /opt/exo/lib (medido em
 * 2026-08-27) porque a propria plataforma os usa para indexar conteudo. Este
 * extrator, portanto, nao acrescenta um unico jar a' imagem -- usa o que ja'
 * roda. Menos superficie de ataque, menos memoria, nada para atualizar em
 * separado.
 *
 * <p><b>TETO DE CARACTERES, E POR QUE O ESTOURO NAO E' ERRO.</b> Documento
 * grande extraido sem limite e' o caminho curto para OutOfMemory num heap que
 * ja' opera a 92% (medido no exo-app). O {@link BodyContentHandler} com limite
 * lanca {@link SAXException} ao atinge-lo -- e nesse ponto o texto ja' colhido
 * E' VALIDO. Trata-lo como falha jogaria fora uma varredura boa; por isso o
 * parcial e' devolvido, e quem decide o que fazer com "varredura incompleta"
 * e' a politica, nao este extrator.
 *
 * <p><b>NAO FECHA O FLUXO</b>, por contrato de {@link Extrator}: quem abriu a
 * propriedade JCR e' quem tem de fecha-la, e fechar aqui esconderia vazamento
 * de quem chama.
 */
public final class ExtratorTika implements Extrator {

  /** 1 milhao de caracteres ~ 2 MiB de texto. Muito acima de qualquer oficio,
   *  muito abaixo do que ameaca o heap. */
  public static final int TETO_CARACTERES_PADRAO = 1_000_000;

  private final int tetoCaracteres;

  public ExtratorTika() {
    this(TETO_CARACTERES_PADRAO);
  }

  public ExtratorTika(int tetoCaracteres) {
    if (tetoCaracteres < 1) {
      throw new IllegalArgumentException("teto de caracteres tem de ser >= 1");
    }
    this.tetoCaracteres = tetoCaracteres;
  }

  public int getTetoCaracteres() {
    return tetoCaracteres;
  }

  /**
   * Aceita qualquer coisa: o AutoDetectParser decide pelo CONTEUDO, nao pela
   * extensao. E' de proposito que este extrator seja o ultimo da fila -- ele
   * e' o generalista, e o especialista (texto puro) responde antes por ser
   * mais barato.
   *
   * <p>Nao confiar em nome nem em mime aqui tambem e' defesa: os dois vem do
   * cliente. Um .txt renomeado para .pdf, ou um mime mentiroso, nao escapa da
   * varredura -- o Tika olha os bytes.
   */
  @Override
  public boolean aceita(String nomeArquivo, String tipoMime) {
    return true;
  }

  @Override
  public String extrair(InputStream entrada, String nomeArquivo, String tipoMime)
      throws ExtracaoIndisponivelException, IOException {
    if (entrada == null) {
      throw new ExtracaoIndisponivelException("fluxo de entrada nulo");
    }

    BodyContentHandler manipulador = new BodyContentHandler(tetoCaracteres);
    Metadata metadados = new Metadata();
    // Dicas ao detector. Sao PISTAS, nao verdade: o Tika as usa para desempatar,
    // e ignora quando os bytes dizem outra coisa.
    if (nomeArquivo != null && !nomeArquivo.isEmpty()) {
      metadados.set("resourceName", nomeArquivo);
    }
    if (tipoMime != null && !tipoMime.isEmpty()) {
      metadados.set("Content-Type", tipoMime);
    }

    try {
      new AutoDetectParser().parse(entrada, manipulador, metadados, new ParseContext());
    } catch (SAXException e) {
      // Teto atingido. O texto ja' colhido e' legitimo e provavelmente contem o
      // que interessa -- o cabecalho de um documento e' onde o CPF costuma
      // estar. Devolver o parcial e' melhor DLP do que devolver nada.
      String parcial = manipulador.toString();
      if (parcial != null && !parcial.isEmpty()) {
        return parcial;
      }
      throw new ExtracaoIndisponivelException(
          "Tika interrompeu a leitura antes de qualquer texto: " + e.getMessage(), e);
    } catch (TikaException e) {
      // Formato desconhecido, arquivo corrompido ou PDF cifrado. E' excecao
      // VERIFICADA de proposito: obriga quem chama a decidir. Um documento que
      // nao pode ser lido nao e' um documento limpo.
      throw new ExtracaoIndisponivelException(
          "conteudo nao pode ser interpretado (formato, corrupcao ou cifra): "
              + e.getMessage(), e);
    } catch (RuntimeException e) {
      // Parser de terceiro pode estourar de forma nao declarada. Traduzir para
      // a excecao do contrato impede que uma falha de UM arquivo derrube o job
      // que varre o acervo INTEIRO.
      throw new ExtracaoIndisponivelException(
          "falha inesperada do parser: " + e.getClass().getSimpleName()
              + ": " + e.getMessage(), e);
    }

    return manipulador.toString();
  }
}
