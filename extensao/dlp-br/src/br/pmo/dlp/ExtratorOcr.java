package br.pmo.dlp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Inspecao OCR: le texto de IMAGEM chamando um motor externo de linha de
 * comando (Tesseract e' o alvo natural, por ser software livre).
 *
 * <h2>Por que este arquivo existe mesmo sem motor instalado</h2>
 * Nao ha' motor de OCR nesta stack, e subir um e' decisao de infraestrutura --
 * container novo, memoria, e o operador tem a palavra. Mas o CODIGO nao depende
 * dessa decisao: escrito agora, ele fica pronto e a ativacao vira uma linha de
 * configuracao. Enquanto o motor nao existir, este extrator falha de forma
 * HONESTA -- {@link ExtracaoIndisponivelException} com o motivo por extenso --
 * e o item cai em {@link CategoriaConformidade#NAO_VARRIDO} com motivo
 * {@link MotivoNaoVarrido#PROVAVEL_DIGITALIZACAO}. O relatorio entao mostra,
 * em numero absoluto e em percentual do acervo, quantos documentos estao fora
 * do alcance do DLP por falta de OCR. E' esse numero que transforma "vale a
 * pena investir em OCR?" de pergunta abstrata em planilha de orcamento.
 *
 * <p>O que este arquivo <b>nao</b> faz, e nao deve fazer: instalar, baixar ou
 * subir motor de OCR. Isso e' provisionamento de infraestrutura.
 *
 * <h2>Limite conhecido: PDF digitalizado</h2>
 * Aceita IMAGEM. Um PDF que e' digitalizacao precisa antes ser rasterizado
 * pagina a pagina, e o rasterizador ({@code pdftoppm}, ou o PDFBox que ja'
 * existe na plataforma) e' outro componente. Enquanto ele nao existir, o PDF
 * digitalizado continua caindo em NAO_VARRIDO -- que e' o comportamento certo,
 * so' que sem a leitura que o OCR daria.
 *
 * <h2>Executar processo externo com entrada de terceiro: as cinco travas</h2>
 * <ol>
 *   <li><b>Nunca ha' interpretador de comandos.</b> {@link ProcessBuilder}
 *       recebe VETOR de argumentos. Nao existe string de comando para alguem
 *       injetar {@code ; rm -rf}.</li>
 *   <li><b>O nome do arquivo do usuario nunca vai para a linha de comando.</b>
 *       O conteudo e' gravado num temporario de nome GERADO. Nome de arquivo e'
 *       texto controlado por quem envia -- {@code "-c foo.png"} ou
 *       {@code "--tessdata-dir /etc"} viraria opcao do proprio motor.</li>
 *   <li><b>O comando e' caminho ABSOLUTO vindo de configuracao</b>, conferido
 *       como arquivo executavel antes de rodar. Nunca deduzido da entrada nem
 *       procurado no PATH, que e' herdado e pode ser plantado.</li>
 *   <li><b>Teto de tempo com morte forcada.</b> OCR em imagem grande demora, e
 *       imagem construida para demorar existe. Sem teto, cada uma dessas prende
 *       uma thread do portal para sempre.</li>
 *   <li><b>O temporario e' criado so' para o dono e apagado no
 *       {@code finally}.</b> Ele contem, por definicao, o documento sensivel.
 *       Deixa-lo em {@code /tmp} legivel por todos seria trocar o vazamento de
 *       lugar em vez de conte-lo.</li>
 * </ol>
 */
public final class ExtratorOcr implements Extrator {

  /** Teto padrao de tempo do processo externo, em milissegundos. */
  public static final long TETO_MILISSEGUNDOS_PADRAO = 30_000L;

  /** Teto padrao de bytes de imagem aceitos. */
  public static final int TETO_BYTES_PADRAO = 32 * 1024 * 1024;

  /** Idiomas padrao do Tesseract: portugues com ingles de reserva. */
  public static final String IDIOMAS_PADRAO = "por+eng";

  private static final Set<String> EXTENSOES_DE_IMAGEM = new HashSet<>(Arrays.asList(
      "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "pnm", "jp2"));

  private final String comando;

  private final String idiomas;

  private final long tetoMilissegundos;

  private final int tetoBytes;

  /**
   * @param comando           caminho ABSOLUTO do executavel de OCR (por
   *                          exemplo {@code /usr/bin/tesseract}); nulo ou vazio
   *                          significa OCR desligado, e todo item cai em falha
   *                          honesta
   * @param idiomas           codigo de idiomas do motor; nulo usa
   *                          {@link #IDIOMAS_PADRAO}
   * @param tetoMilissegundos teto de tempo do processo
   * @param tetoBytes         teto de bytes da imagem
   */
  public ExtratorOcr(String comando, String idiomas, long tetoMilissegundos, int tetoBytes) {
    if (tetoMilissegundos <= 0) {
      throw new IllegalArgumentException("teto de tempo tem de ser positivo");
    }
    if (tetoBytes <= 0) {
      throw new IllegalArgumentException("teto de bytes tem de ser positivo");
    }
    this.comando = comando == null ? "" : comando.trim();
    this.idiomas = idiomas == null || idiomas.trim().isEmpty() ? IDIOMAS_PADRAO : idiomas.trim();
    this.tetoMilissegundos = tetoMilissegundos;
    this.tetoBytes = tetoBytes;
  }

  /** OCR desligado: existe, aceita imagem, e falha dizendo por que. */
  public ExtratorOcr() {
    this("", IDIOMAS_PADRAO, TETO_MILISSEGUNDOS_PADRAO, TETO_BYTES_PADRAO);
  }

  /**
   * Se ha' motor configurado e utilizavel AGORA. Falso nao desliga o extrator:
   * ele continua aceitando imagem e falhando com motivo, que e' o que faz o
   * item aparecer no relatorio em vez de sumir.
   */
  public boolean temMotor() {
    if (comando.isEmpty()) {
      return false;
    }
    Path caminho = Path.of(comando);
    return caminho.isAbsolute() && Files.isRegularFile(caminho) && Files.isExecutable(caminho);
  }

  public String getComando() {
    return comando;
  }

  public String getIdiomas() {
    return idiomas;
  }

  /**
   * Aceita imagem, e SO' imagem. Aceitar tambem quando nao ha' motor e'
   * deliberado: e' assim que o item vira uma linha de "exige OCR" no relatorio,
   * em vez de passar por documento sem texto.
   */
  @Override
  public boolean aceita(String nomeArquivo, String tipoMime) {
    if (tipoMime != null && tipoMime.toLowerCase(Locale.ROOT).trim().startsWith("image/")) {
      return true;
    }
    String extensao = ExtratorTextoSimples.extensaoDe(nomeArquivo);
    return extensao != null && EXTENSOES_DE_IMAGEM.contains(extensao);
  }

  @Override
  public String extrair(InputStream entrada, String nomeArquivo, String tipoMime)
      throws ExtracaoIndisponivelException, IOException {
    if (entrada == null) {
      throw new ExtracaoIndisponivelException("fluxo nulo");
    }
    if (!aceita(nomeArquivo, tipoMime)) {
      throw new ExtracaoIndisponivelException(
          "este extrator so' trata imagem: nome=" + nomeArquivo + " mime=" + tipoMime);
    }
    if (comando.isEmpty()) {
      throw new ExtracaoIndisponivelException(
          "motor de OCR nao configurado (exo.dlp.ocr.comando vazio): a imagem"
          + (nomeArquivo == null ? "" : " " + nomeArquivo)
          + " NAO foi lida e NAO pode ser considerada livre de dados sensiveis."
          + " Exige inspecao OCR.");
    }
    if (!temMotor()) {
      throw new ExtracaoIndisponivelException(
          "motor de OCR configurado em '" + comando + "' nao e' um executavel"
          + " acessivel: a imagem NAO foi lida. Exige inspecao OCR.");
    }

    byte[] imagem = lerAte(entrada, tetoBytes);
    if (imagem.length == 0) {
      throw new ExtracaoIndisponivelException("imagem vazia");
    }

    Path temporario = null;
    try {
      temporario = gravarTemporario(imagem);
      return rodarMotor(temporario, nomeArquivo);
    } finally {
      apagar(temporario);
    }
  }

  // ===========================================================================

  private static byte[] lerAte(InputStream entrada, int teto) throws IOException {
    ByteArrayOutputStream saida = new ByteArrayOutputStream(Math.min(teto, 64 * 1024));
    byte[] balde = new byte[8192];
    int total = 0;
    int lidos;
    while (total < teto
           && (lidos = entrada.read(balde, 0, Math.min(balde.length, teto - total))) != -1) {
      saida.write(balde, 0, lidos);
      total += lidos;
    }
    return saida.toByteArray();
  }

  /**
   * Grava num temporario de nome gerado e permissao 0600. O nome do usuario nao
   * participa nem do nome do arquivo -- nem para "ajudar a depurar".
   */
  private static Path gravarTemporario(byte[] imagem) throws IOException {
    Path arquivo;
    try {
      Set<PosixFilePermission> soDono = PosixFilePermissions.fromString("rw-------");
      arquivo = Files.createTempFile("dlp-ocr-", ".img",
                                     PosixFilePermissions.asFileAttribute(soDono));
    } catch (UnsupportedOperationException e) {
      // Sistema de arquivos sem POSIX: cria assim mesmo, mas registra o risco
      // no proprio codigo em vez de fingir que a permissao foi aplicada.
      arquivo = Files.createTempFile("dlp-ocr-", ".img");
    }
    Files.write(arquivo, imagem);
    return arquivo;
  }

  /**
   * Roda o motor com {@code stdout} como saida ({@code -} no Tesseract) e
   * devolve o texto. Erro do motor NAO vira texto vazio: vira excecao.
   */
  private String rodarMotor(Path imagem, String nomeArquivo)
      throws ExtracaoIndisponivelException, IOException {
    List<String> argumentos = new ArrayList<>();
    argumentos.add(comando);
    argumentos.add(imagem.toAbsolutePath().toString());
    argumentos.add("-");            // escreve o texto em stdout
    argumentos.add("-l");
    argumentos.add(idiomas);

    ProcessBuilder construtor = new ProcessBuilder(argumentos);
    construtor.redirectErrorStream(false);
    Process processo = construtor.start();

    // Fecha a entrada: motor que espera dados de stdin travaria ate' o teto.
    processo.getOutputStream().close();

    byte[] saida = lerAte(processo.getInputStream(), tetoBytes);
    byte[] erro = lerAte(processo.getErrorStream(), 8192);

    boolean terminou;
    try {
      terminou = processo.waitFor(tetoMilissegundos, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      processo.destroyForcibly();
      throw new ExtracaoIndisponivelException("OCR interrompido", e);
    }
    if (!terminou) {
      processo.destroyForcibly();
      throw new ExtracaoIndisponivelException(
          "OCR passou do teto de " + tetoMilissegundos + " ms"
          + (nomeArquivo == null ? "" : " em " + nomeArquivo)
          + " e foi encerrado: a imagem NAO foi lida.");
    }
    if (processo.exitValue() != 0) {
      throw new ExtracaoIndisponivelException(
          "motor de OCR terminou com codigo " + processo.exitValue()
          + (nomeArquivo == null ? "" : " em " + nomeArquivo) + ": "
          + new String(erro, StandardCharsets.UTF_8).trim());
    }

    String texto = new String(saida, StandardCharsets.UTF_8);
    if (texto.trim().isEmpty()) {
      throw new ExtracaoIndisponivelException(
          "o OCR rodou e nao devolveu texto"
          + (nomeArquivo == null ? "" : " em " + nomeArquivo)
          + ": imagem sem escrita legivel, ou qualidade insuficiente."
          + " NAO pode ser considerada livre de dados sensiveis.");
    }
    return texto;
  }

  /** Apagar o temporario nao e' opcional: ele contem o documento sensivel. */
  private static void apagar(Path arquivo) {
    if (arquivo == null) {
      return;
    }
    try {
      Files.deleteIfExists(arquivo);
    } catch (IOException e) {
      // Nao ha' a quem relancar dentro do finally sem mascarar a excecao
      // original. Marca para o encerramento da JVM e segue.
      arquivo.toFile().deleteOnExit();
    }
  }
}
