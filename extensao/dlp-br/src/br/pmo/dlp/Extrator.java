package br.pmo.dlp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Contrato de extracao de texto: de bytes para {@code String} varrivel.
 *
 * <p><b>POR QUE ISTO E' UMA INTERFACE, E NAO UM METODO ESTATICO.</b> O nucleo
 * deste pacote nao depende de NADA fora do JDK -- e' o que permite compila-lo e
 * prova-lo fora do container, na maquina, em segundos. Extrair texto de PDF, de
 * .docx e de imagem exige Tika, PDFBox, POI e um motor de OCR, que sao
 * bibliotecas grandes e vivem dentro da plataforma. Se o nucleo importasse Tika,
 * a prova do motor de deteccao passaria a exigir subir o eXo inteiro, e a
 * primeira coisa que se abandona num prazo apertado e' o teste que demora.
 *
 * <p>Entao a fronteira e' aqui: o nucleo declara o contrato, o adaptador que
 * roda dentro do portal implementa com Tika, e o OCR entra depois como mais uma
 * implementacao -- sem redesenhar nada.
 *
 * <h2>Regra que toda implementacao deve obedecer</h2>
 * <b>Nao conseguir extrair NAO pode virar string vazia.</b> String vazia entra
 * no {@link Varredura} e sai como "documento limpo", e um PDF ilegivel seria
 * classificado como PUBLICO. Quem nao consegue extrair lanca
 * {@link ExtracaoIndisponivelException}, e quem chama decide -- a politica trata
 * documento nao extraido como suspeito, jamais como limpo.
 */
public interface Extrator {

  /**
   * Se esta implementacao se propoe a tratar este arquivo.
   *
   * @param nomeArquivo nome com extensao, pode ser nulo
   * @param tipoMime    tipo declarado, pode ser nulo ou mentiroso -- e' dado do
   *                    cliente, entao nunca e' a unica fonte de verdade
   */
  boolean aceita(String nomeArquivo, String tipoMime);

  /**
   * Le o fluxo e devolve o texto. NAO fecha o fluxo: quem abriu, fecha.
   *
   * @throws ExtracaoIndisponivelException quando o formato nao e' suportado ou
   *                                       o conteudo esta' corrompido/cifrado
   * @throws IOException                   falha de leitura do fluxo
   */
  String extrair(InputStream entrada, String nomeArquivo, String tipoMime)
      throws ExtracaoIndisponivelException, IOException;

  /**
   * Sinaliza que o texto NAO pode ser obtido. Verificada de proposito: obriga
   * quem chama a decidir o que fazer, em vez de deixar o caso silencioso.
   */
  class ExtracaoIndisponivelException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExtracaoIndisponivelException(String motivo) {
      super(motivo);
    }

    public ExtracaoIndisponivelException(String motivo, Throwable causa) {
      super(motivo, causa);
    }
  }
}
