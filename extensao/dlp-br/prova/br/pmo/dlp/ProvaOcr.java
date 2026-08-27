package br.pmo.dlp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import br.pmo.dlp.Extrator.ExtracaoIndisponivelException;

/**
 * Prova do extrator de OCR SEM motor instalado -- que e' o estado desta
 * instalacao e vai continuar sendo ate' o operador decidir sobre o container.
 *
 * <p>A pergunta que estas asseveracoes respondem: <b>com o OCR desligado, a
 * imagem some do radar ou aparece no relatorio?</b> Se sumir, o DLP mente. Se
 * aparecer, o numero de imagens nao lidas vira o argumento de orcamento para
 * ligar o OCR.
 */
final class ProvaOcr {

  private ProvaOcr() {
  }

  static void rodar() {
    aceitaImagemEMaisNada();
    desligadoFalhaComMotivo();
    caminhoInvalidoNaoEExecutavel();
    aImagemAPARECENoRelatorio();
  }

  private static void aceitaImagemEMaisNada() {
    Prova.secao("OCR — aceita imagem, e so' imagem");

    ExtratorOcr ocr = new ExtratorOcr();
    Prova.certo("aceita .png pelo nome", ocr.aceita("digitalizado.png", null));
    Prova.certo("aceita .tiff pelo nome", ocr.aceita("scan.tiff", null));
    Prova.certo("aceita image/jpeg pelo mime", ocr.aceita(null, "image/jpeg"));
    Prova.certo("NAO aceita .pdf — precisa de rasterizador antes, que nao existe",
                !ocr.aceita("contrato.pdf", "application/pdf"));
    Prova.certo("NAO aceita .txt", !ocr.aceita("nota.txt", "text/plain"));
    Prova.certo("NAO aceita .docx", !ocr.aceita("memorando.docx", null));
    Prova.certo("sem motor configurado, temMotor() e' falso", !ocr.temMotor());
  }

  private static void desligadoFalhaComMotivo() {
    Prova.secao("OCR — desligado, falha HONESTA em vez de devolver texto vazio");

    ExtratorOcr ocr = new ExtratorOcr();
    String motivo = null;
    try {
      ocr.extrair(new ByteArrayInputStream("PNG-falso".getBytes(StandardCharsets.UTF_8)),
                  "ficha-funcional.png", "image/png");
      Prova.certo("devia ter lancado excecao", false);
    } catch (ExtracaoIndisponivelException e) {
      motivo = e.getMessage();
    } catch (Exception e) {
      Prova.certo("excecao inesperada: " + e, false);
    }

    Prova.certo("lancou ExtracaoIndisponivel", motivo != null);
    System.out.println("   ..   " + motivo);
    Prova.certo("o motivo diz que o motor nao esta' configurado",
                motivo != null && motivo.contains("nao configurado"));
    Prova.certo("e diz, sem eufemismo, que a imagem NAO pode ser dada por limpa",
                motivo != null && motivo.contains("NAO pode ser considerada livre"));
    Prova.certo("e nomeia o parametro que liga o recurso",
                motivo != null && motivo.contains("exo.dlp.ocr.comando"));
  }

  private static void caminhoInvalidoNaoEExecutavel() {
    Prova.secao("OCR — caminho de motor invalido nao vira execucao");

    Prova.certo("caminho relativo NAO e' aceito como motor (PATH e' herdado e plantavel)",
                !new ExtratorOcr("tesseract", null, 1000L, 1024).temMotor());
    Prova.certo("caminho absoluto inexistente tambem nao",
                !new ExtratorOcr("/usr/bin/motor-que-nao-existe", null, 1000L, 1024).temMotor());
    Prova.certo("diretorio nao e' executavel", !new ExtratorOcr("/tmp", null, 1000L, 1024).temMotor());

    String motivo = null;
    try {
      new ExtratorOcr("/usr/bin/motor-que-nao-existe", null, 1000L, 1024)
          .extrair(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "x.png", "image/png");
    } catch (ExtracaoIndisponivelException e) {
      motivo = e.getMessage();
    } catch (Exception e) {
      Prova.certo("excecao inesperada: " + e, false);
    }
    Prova.certo("e a falha diz qual caminho foi tentado",
                motivo != null && motivo.contains("/usr/bin/motor-que-nao-existe"));

    boolean recusou = false;
    try {
      new ExtratorOcr("/usr/bin/tesseract", null, 0L, 1024);
    } catch (IllegalArgumentException e) {
      recusou = true;
    }
    Prova.certo("teto de tempo zero e' recusado na construcao (processo eterno)", recusou);
  }

  /**
   * O fecho: a imagem que o OCR nao leu tem de aparecer no relatorio, na gaveta
   * que justifica orcamento. Se ela sumisse, o acervo pareceria mais limpo do
   * que e'.
   */
  private static void aImagemAPARECENoRelatorio() {
    Prova.secao("OCR — a imagem nao lida APARECE no relatorio, na gaveta do orcamento");

    ExtratorOcr ocr = new ExtratorOcr();
    String motivo;
    try {
      ocr.extrair(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "rg-do-requerente.png", "image/png");
      motivo = null;
    } catch (Exception e) {
      motivo = e.getMessage();
    }

    RelatorioConformidade relatorio = new RelatorioConformidade("acervo com digitalizacao");
    relatorio.registrar("rg-do-requerente.png",
                        new Varredura().varrerParcial("rg-do-requerente.png\n", motivo));
    relatorio.registrar("ata.txt", new Varredura().varrer("Ata comum."));
    InstantaneoConformidade foto = relatorio.instantaneo();

    Prova.igual("a imagem conta como NAO VARRIDO", 1,
                foto.getQuantidade(CategoriaConformidade.NAO_VARRIDO));
    Prova.igual("na gaveta que pede OCR", 1,
                foto.getQuantidade(MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO));
    Prova.igual("com percentual do acervo calculado", "50.00",
                String.format(java.util.Locale.ROOT, "%.2f",
                              foto.getPercentual(MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO)));
    Prova.certo("e a referencia do item esta' na amostra, para o administrador conferir",
                foto.getAmostras(MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO)
                    .contains("rg-do-requerente.png"));
    Prova.certo("o relatorio em texto traz o encaminhamento 'exige OCR'",
                foto.emTexto().contains("exige OCR"));
  }
}
