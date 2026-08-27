package br.pmo.dlp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import br.pmo.dlp.Extrator.ExtracaoIndisponivelException;

/**
 * Prova da extracao de texto: codificacao, marcacao e -- de novo o ponto que
 * decide se o DLP protege ou finge -- o que acontece quando NAO da' para
 * extrair.
 */
final class ProvaExtrator {

  private ProvaExtrator() {
  }

  static void rodar() {
    aceitaOqueDeveAceitar();
    codificacaoBrasileira();
    marcacaoNaoColaNumeros();
    naoExtrairNaoEStringVazia();
  }

  private static void aceitaOqueDeveAceitar() {
    Prova.secao("Extrator — o que ele se propoe a tratar");

    ExtratorTextoSimples e = new ExtratorTextoSimples();
    Prova.certo("aceita .csv", e.aceita("servidores.csv", null));
    Prova.certo("aceita .txt", e.aceita("oficio.txt", null));
    Prova.certo("aceita text/plain sem nome", e.aceita(null, "text/plain; charset=utf-8"));
    Prova.certo("aceita application/json", e.aceita(null, "application/json"));
    Prova.certo("NAO aceita .pdf (tem extrator proprio no portal)",
                !e.aceita("contrato.pdf", "application/pdf"));
    Prova.certo("NAO aceita .docx", !e.aceita("memorando.docx", null));
    Prova.certo("NAO aceita imagem (isso e' trabalho do OCR)",
                !e.aceita("digitalizado.png", "image/png"));
  }

  private static void codificacaoBrasileira() {
    Prova.secao("Extrator — CSV legado em ISO-8859-1 e' lido sem corromper posicao");

    String conteudo = "nome;cpf\nJosé Gonçalves;" + ProvaRegras.CPF_VALIDO_1 + "\n";

    String lidoLatin = extrair(conteudo.getBytes(StandardCharsets.ISO_8859_1), "legado.csv");
    Prova.certo("acentuacao Latin-1 preservada", lidoLatin.contains("José Gonçalves"));
    Prova.certo("nenhum caractere de substituicao U+FFFD", lidoLatin.indexOf('�') < 0);

    String lidoUtf8 = extrair(conteudo.getBytes(StandardCharsets.UTF_8), "moderno.csv");
    Prova.certo("UTF-8 tambem preservado", lidoUtf8.contains("José Gonçalves"));

    byte[] comBom = new byte[3 + conteudo.getBytes(StandardCharsets.UTF_8).length];
    comBom[0] = (byte) 0xEF;
    comBom[1] = (byte) 0xBB;
    comBom[2] = (byte) 0xBF;
    System.arraycopy(conteudo.getBytes(StandardCharsets.UTF_8), 0, comBom, 3, comBom.length - 3);
    String lidoBom = extrair(comBom, "exportado.csv");
    Prova.certo("BOM de UTF-8 nao vira caractere no inicio do texto",
                lidoBom.startsWith("nome;cpf"));

    Varredura motor = new Varredura();
    Prova.certo("o CPF e' achado nas tres codificacoes",
                motor.varrer(lidoLatin).getAchado("CPF") != null
                && motor.varrer(lidoUtf8).getAchado("CPF") != null
                && motor.varrer(lidoBom).getAchado("CPF") != null);
  }

  /**
   * O caso silencioso: {@code <td>CPF</td><td>2</td>} sem separador cola os
   * digitos e o numero deixa de fechar no modulo 11 -- a tabela inteira passaria
   * despercebida.
   */
  private static void marcacaoNaoColaNumeros() {
    Prova.secao("Extrator — etiqueta HTML vira ESPACO, nunca some");

    String html = "<html><body><table>"
        + "<tr><td>" + ProvaRegras.CPF_VALIDO_1 + "</td><td>2</td></tr>"
        + "<tr><td>" + ProvaRegras.CPF_VALIDO_2 + "</td><td>3</td></tr>"
        + "</table><script>var cpf='000.000.000-00';</script></body></html>";

    String texto = extrair(html.getBytes(StandardCharsets.UTF_8), "relatorio.html");
    System.out.println("   ..   texto: " + texto.trim().replaceAll("\\s+", " "));

    ResultadoVarredura r = new Varredura().varrer(texto);
    Prova.igual("os dois CPFs da tabela foram achados", 2,
                r.getAchado("CPF") == null ? 0 : r.getAchado("CPF").getQuantidade());
    Prova.certo("o conteudo de <script> foi descartado", !texto.contains("var cpf"));

    String xml = "<pessoas><p><cpf>" + ProvaRegras.CPF_VALIDO_1 + "</cpf><idade>2</idade></p></pessoas>";
    ResultadoVarredura rx = new Varredura()
        .varrer(extrair(xml.getBytes(StandardCharsets.UTF_8), "dados.xml"));
    Prova.certo("em XML tambem", rx.getAchado("CPF") != null);

    String entidades = "<p>contato &lt;gabinete@pmo.gov.br&gt;</p>";
    Prova.certo("entidades XML sao desescapadas",
                extrair(entidades.getBytes(StandardCharsets.UTF_8), "p.html")
                    .contains("<gabinete@pmo.gov.br>"));
  }

  /** Falha de extracao TEM de ser excecao, nunca texto vazio. */
  private static void naoExtrairNaoEStringVazia() {
    Prova.secao("Extrator — o que nao da' para ler nao vira 'documento limpo'");

    ExtratorTextoSimples e = new ExtratorTextoSimples();

    boolean recusouPdf = false;
    try {
      e.extrair(new ByteArrayInputStream("%PDF-1.7".getBytes(StandardCharsets.UTF_8)),
                "contrato.pdf", "application/pdf");
    } catch (ExtracaoIndisponivelException ex) {
      recusouPdf = true;
      System.out.println("   ..   " + ex.getMessage());
    } catch (Exception ex) {
      System.out.println("   ..   excecao inesperada: " + ex);
    }
    Prova.certo("PDF lanca ExtracaoIndisponivel (nao devolve \"\")", recusouPdf);

    // Arquivo binario com extensao mentindo -- o caso do usuario que renomeia
    // para escapar de filtro por extensao.
    byte[] binario = new byte[] { 'P', 'K', 3, 4, 0, 0, 0, 0, 'l', 'i', 'x', 'o' };
    boolean recusouBinario = false;
    try {
      e.extrair(new ByteArrayInputStream(binario), "planilha.csv", "text/csv");
    } catch (ExtracaoIndisponivelException ex) {
      recusouBinario = true;
      System.out.println("   ..   " + ex.getMessage());
    } catch (Exception ex) {
      System.out.println("   ..   excecao inesperada: " + ex);
    }
    Prova.certo("ZIP renomeado para .csv e' recusado, nao lido como texto", recusouBinario);

    String vazio = extrair(new byte[0], "vazio.txt");
    Prova.igual("arquivo realmente vazio devolve texto vazio (isso e' legitimo)", "", vazio);
  }

  private static String extrair(byte[] bytes, String nome) {
    try {
      return new ExtratorTextoSimples()
          .extrair(new ByteArrayInputStream(bytes), nome, null);
    } catch (Exception e) {
      Prova.certo("extracao de " + nome + " nao devia falhar: " + e, false);
      return "";
    }
  }
}
