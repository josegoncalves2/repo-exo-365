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
    osTresFurosDaAuditoria();
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
    // ASSEVERACAO INVERTIDA em 2026-08-27, e de proposito. Ela afirmava
    // "o conteudo de <script> foi descartado", que era o comportamento ANTIGO e
    // era o defeito: descartar script desligava em silencio a regra
    // SEGREDO_EM_TEXTO_CLARO deste mesmo pacote, que procura senha= e api_key=
    // justamente dentro de script e de bloco de configuracao. Uma prova que
    // trava o comportamento errado e' pior que prova nenhuma, porque impede a
    // correcao com ar de rigor.
    Prova.certo("o conteudo de <script> agora e' VARRIDO, nao descartado",
                texto.contains("var cpf"));
    Prova.certo("mas as etiquetas continuam virando espaco",
                !texto.contains("<script>") && !texto.contains("</script>"));

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

  /**
   * Regressao dos tres furos achados na auditoria de 2026-08-27, disparada pelo
   * aviso da sessao projetos-97 ("correcao esquecida num ramo e' correcao nao
   * feita" e "confere onde tu decides por prefixo").
   */
  private static void osTresFurosDaAuditoria() {
    Prova.secao("Extrator — FURO 1: binario escondido depois dos primeiros 8 KiB");

    // 8 KiB de texto legitimo na frente, binario atras. A checagem antiga so'
    // olhava o comeco e daria o arquivo por texto.
    StringBuilder disfarce = new StringBuilder();
    while (disfarce.length() < 9000) {
      disfarce.append("relatorio administrativo sem dado pessoal. ");
    }
    byte[] cabeca = disfarce.toString().getBytes(StandardCharsets.ISO_8859_1);
    byte[] arquivo = new byte[cabeca.length + 64];
    System.arraycopy(cabeca, 0, arquivo, 0, cabeca.length);
    for (int i = 0; i < 64; i++) {
      arquivo[cabeca.length + i] = (byte) (i % 2 == 0 ? 0 : 0xC3);
    }

    boolean recusou = false;
    String motivo = null;
    try {
      new ExtratorTextoSimples()
          .extrair(new ByteArrayInputStream(arquivo), "disfarce.txt", "text/plain");
    } catch (ExtracaoIndisponivelException e) {
      recusou = true;
      motivo = e.getMessage();
    } catch (Exception e) {
      Prova.certo("excecao inesperada: " + e, false);
    }
    Prova.certo("binario apos 8 KiB de texto e' recusado (varre o buffer inteiro)", recusou);
    System.out.println("   ..   " + motivo);

    Prova.secao("Extrator — FURO 2: BOM desviava da checagem de binario");

    // Prefixar FF FE a um binario qualquer tomava o ramo do BOM, que declarava
    // "nao binario" sem olhar mais nada. O binario virava UTF-16, virava lixo,
    // nao casava com regra nenhuma e saia do motor como DOCUMENTO LIMPO.
    byte[] lixo = new byte[512];
    for (int i = 0; i < lixo.length; i++) {
      lixo[i] = (byte) ((i * 37 + 11) & 0xFF);
    }
    byte[] comBomFalso = new byte[2 + lixo.length];
    comBomFalso[0] = (byte) 0xFF;
    comBomFalso[1] = (byte) 0xFE;
    System.arraycopy(lixo, 0, comBomFalso, 2, lixo.length);

    boolean recusouBom = false;
    String motivoBom = null;
    try {
      new ExtratorTextoSimples()
          .extrair(new ByteArrayInputStream(comBomFalso), "planilha.csv", "text/csv");
    } catch (ExtracaoIndisponivelException e) {
      recusouBom = true;
      motivoBom = e.getMessage();
    } catch (Exception e) {
      Prova.certo("excecao inesperada: " + e, false);
    }
    Prova.certo("binario com BOM falso NAO passa mais por texto", recusouBom);
    System.out.println("   ..   " + motivoBom);

    // O criterio tem de separar com folga, nao por um fio. Estes tres sao os
    // que quase viraram falso positivo: acentuacao, escrita nao latina, e
    // emoji -- que e' PAR DE SUBSTITUTOS e reprovaria numa contagem por char.
    String acentuado = "Deliberou-se pela aprovacao do parecer no 004/2026, a unanimidade."
        + " Acao, coracao, atencao, execucao,. Texto longo o bastante para a proporcao valer.";
    Prova.certo("documento com acentuacao passa",
                extrair(acentuado.getBytes(StandardCharsets.UTF_8), "parecer.txt").length() > 0);

    StringBuilder cjk = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      cjk.append((char) (0x4E00 + i));
    }
    Prova.certo("texto CJK passa (escrita nao latina nao e' lixo)",
                extrair(cjk.toString().getBytes(StandardCharsets.UTF_8), "cjk.txt").length() > 0);

    StringBuilder comEmoji = new StringBuilder("Relatorio mensal aprovado pela comissao. ");
    for (int i = 0; i < 60; i++) {
      comEmoji.appendCodePoint(0x1F600 + (i % 60));
    }
    Prova.certo("texto com EMOJI passa — contagem por code point, nao por char",
                extrair(comEmoji.toString().getBytes(StandardCharsets.UTF_8), "chat.txt").length() > 0);

    // E o inverso tem de continuar valendo: UTF-16 de verdade e' texto.
    String oficio = "OFICIO 145/2026 — requerente CPF " + ProvaRegras.CPF_VALIDO_1
                    + ", contato gabinete@pmo.gov.br, e mais texto para passar do minimo.";
    byte[] utf16 = new byte[2 + oficio.getBytes(java.nio.charset.StandardCharsets.UTF_16LE).length];
    utf16[0] = (byte) 0xFF;
    utf16[1] = (byte) 0xFE;
    System.arraycopy(oficio.getBytes(java.nio.charset.StandardCharsets.UTF_16LE), 0,
                     utf16, 2, utf16.length - 2);
    String lidoUtf16 = extrair(utf16, "oficio.txt");
    Prova.certo("UTF-16 legitimo continua sendo lido", lidoUtf16.contains("OFICIO 145/2026"));
    Prova.certo("e o CPF dentro dele e' achado",
                new Varredura().varrer(lidoUtf16).getAchado("CPF") != null);

    Prova.secao("Extrator — FURO 3: comentario e <script> eram descartados COM o conteudo");

    String html = "<html><body>"
        + "<!-- CPF do requerente: " + ProvaRegras.CPF_VALIDO_1 + " -->"
        + "<p>Pagina publica sem dados.</p>"
        + "<script>var conf = {senha: \"correiacavalobateria\"};</script>"
        + "</body></html>";
    String texto = extrair(html.getBytes(StandardCharsets.UTF_8), "pagina.html");
    ResultadoVarredura r = new Varredura().varrer(texto);
    System.out.println("   ..   " + r.resumo());

    Prova.certo("o CPF escondido no COMENTARIO agora e' achado", r.getAchado("CPF") != null);
    Prova.certo("a senha dentro de <script> tambem — a regra propria voltou a valer",
                r.getAchado("SEGREDO_EM_TEXTO_CLARO") != null);
    Prova.igual("e o documento e' classificado como SIGILOSO",
                Classificacao.SIGILOSO, r.getClassificacao());
    Prova.certo("as etiquetas continuam virando espaco (nao colam numeros)",
                !texto.contains("<p>") && !texto.contains("</script>"));
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
