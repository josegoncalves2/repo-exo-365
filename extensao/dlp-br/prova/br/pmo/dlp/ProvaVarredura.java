package br.pmo.dlp;

import java.util.List;

/**
 * Prova do motor: desduplicacao, ordenacao e -- o ponto critico -- que um
 * documento nao varrido por inteiro NUNCA sai daqui parecendo limpo.
 */
final class ProvaVarredura {

  private ProvaVarredura() {
  }

  static void rodar() {
    achaOqueTem();
    desduplicaSobreposicao();
    ordenaPorGravidade();
    naoInventaEmTextoLimpo();
    documentoGrandeNaoViraLimpo();
    textoCapengaNaoViraLimpo();
  }

  private static void achaOqueTem() {
    Prova.secao("Motor — acha o que tem, com a contagem certa");

    String oficio = "OFICIO 145/2026\n"
        + "Interessado: contribuinte inscrito no CPF " + ProvaRegras.CPF_VALIDO_1 + ",\n"
        + "representando a empresa CNPJ " + ProvaRegras.CNPJ_VALIDO_1 + ".\n"
        + "Contato: setor.protocolo@pmo.gov.br, telefone (11) 3221-4455.\n"
        + "Segundo interessado: CPF " + ProvaRegras.CPF_VALIDO_2 + ".\n";

    ResultadoVarredura r = new Varredura().varrer(oficio);

    Prova.igual("dois CPFs contados como dois", 2,
                r.getAchado("CPF") == null ? 0 : r.getAchado("CPF").getQuantidade());
    Prova.igual("um CNPJ", 1,
                r.getAchado("CNPJ") == null ? 0 : r.getAchado("CNPJ").getQuantidade());
    Prova.igual("um e-mail", 1,
                r.getAchado("EMAIL") == null ? 0 : r.getAchado("EMAIL").getQuantidade());
    Prova.certo("varredura completa", r.isCompleta());
    Prova.igual("classificado como SIGILOSO (tem CPF)", Classificacao.SIGILOSO, r.getClassificacao());
    System.out.println("   ..   resumo: " + r.resumo());
    System.out.println("   ..   amostras CPF: " + r.getAchado("CPF").getAmostrasMascaradas());
  }

  /**
   * O caso que separa um DLP util de um gerador de numero inflado.
   */
  private static void desduplicaSobreposicao() {
    Prova.secao("Motor — o mesmo numero nao e' contado duas vezes");

    ResultadoVarredura ambos = new Varredura().varrer("Documento: " + ProvaRegras.CPF_E_CNH);
    Prova.igual("numero valido como CPF E como CNH gera UM achado", 1,
                ambos.getAchados().size());
    Prova.certo("e o achado que sobra e' CPF (mais especifico no catalogo)",
                ambos.getAchado("CPF") != null);
    Prova.certo("CNH nao aparece junto", ambos.getAchado("CNH") == null);
    Prova.igual("total de ocorrencias e' 1", 1, ambos.getTotalOcorrencias());

    ResultadoVarredura soCnh = new Varredura().varrer("Habilitacao " + ProvaRegras.CNH_VALIDA);
    Prova.certo("numero que so' fecha em CNH e' reconhecido como CNH",
                soCnh.getAchado("CNH") != null && soCnh.getAchado("CPF") == null);

    ResultadoVarredura pisOuCnh = new Varredura().varrer("Registro " + ProvaRegras.PIS_E_CNH);
    Prova.igual("numero valido como PIS E como CNH tambem gera UM achado so'", 1,
                pisOuCnh.getAchados().size());
    Prova.certo("empate de severidade (ambos ALTA) -> vence a ordem do catalogo: PIS",
                pisOuCnh.getAchado("PIS_PASEP") != null);
    Prova.certo("o que importa e' que o documento fica SIGILOSO de qualquer forma",
                pisOuCnh.getClassificacao() == Classificacao.SIGILOSO);

    ResultadoVarredura telefone = new Varredura().varrer("Celular 11971295295 do requerente");
    Prova.certo("celular de 11 digitos vira TELEFONE, nao CPF",
                telefone.getAchado("TELEFONE") != null && telefone.getAchado("CPF") == null);
    Prova.igual("e telefone e' BAIXA, logo o documento e' apenas INTERNO",
                Classificacao.INTERNO, telefone.getClassificacao());
  }

  private static void ordenaPorGravidade() {
    Prova.secao("Motor — o mais grave vem primeiro na lista");

    String misto = "e-mail: a@b.gov.br, CEP 01310-100, senha: correiacavalobateria, "
                   + "CPF " + ProvaRegras.CPF_VALIDO_1;
    List<Achado> achados = new Varredura().varrer(misto).getAchados();
    System.out.println("   ..   ordem: " + achados);
    Prova.certo("o primeiro achado e' de severidade ALTA",
                !achados.isEmpty()
                && achados.get(0).getSeveridade() == RegrasSensiveis.Severidade.ALTA);
    boolean naoCresce = true;
    for (int i = 1; i < achados.size(); i++) {
      if (achados.get(i).getSeveridade().compareTo(achados.get(i - 1).getSeveridade()) > 0) {
        naoCresce = false;
      }
    }
    Prova.certo("a severidade nunca sobe ao descer a lista", naoCresce);
  }

  private static void naoInventaEmTextoLimpo() {
    Prova.secao("Motor — texto administrativo limpo nao gera achado nenhum");

    String ata = "ATA DA 12a REUNIAO ORDINARIA\n"
        + "Aos vinte e sete dias do mes de agosto de 2026, reuniu-se a comissao.\n"
        + "Deliberou-se pela aprovacao do parecer 004/2026, por unanimidade.\n"
        + "Valor total homologado: R$ 1.234.567,89. Prazo: 180 dias.\n"
        + "Processo administrativo 52601815908.\n";
    ResultadoVarredura r = new Varredura().varrer(ata);
    Prova.igual("zero achados", 0, r.getAchados().size());
    Prova.certo("documento limpo", r.isLimpo());
    Prova.igual("classificado como PUBLICO", Classificacao.PUBLICO, r.getClassificacao());
    Prova.certo("e a varredura foi completa", r.isCompleta());
  }

  /**
   * O caso que o adaptador do portal vive todo dia: PDF digitalizado, arquivo
   * acima do teto de bytes, formato sem extrator. O texto chega capenga ANTES
   * de entrar no motor, e o motor nao tem como perceber sozinho.
   */
  private static void textoCapengaNaoViraLimpo() {
    Prova.secao("Motor — texto que chegou capenga tambem sai marcado NAO VARRIDO");

    // O que o conector consegue quando nenhum extrator le o binario: so' o nome.
    String soONome = "ficha-funcional-servidor.pdf\nFicha Funcional\n";

    ResultadoVarredura ingenuo = new Varredura().varrer(soONome);
    Prova.certo("varrendo so' o nome, o laudo sai LIMPO e COMPLETO...",
                ingenuo.isLimpo() && ingenuo.isCompleta());
    Prova.igual("...e classificado como PUBLICO — este e' o buraco",
                Classificacao.PUBLICO, ingenuo.getClassificacao());
    Prova.igual("e a politica padrao nao faria nada", PoliticaDlp.Acao.REGISTRAR,
                PoliticaDlp.padrao().decidir(ingenuo).getAcao());

    ResultadoVarredura honesto = new Varredura().varrerParcial(
        soONome, "nenhum extrator leu o binario: provavel digitalizacao, exige OCR");
    Prova.certo("declarando parcial, o mesmo texto sai INCOMPLETO", !honesto.isCompleta());
    Prova.certo("com o motivo preservado",
                honesto.getMotivoIncompleta().contains("OCR"));
    Prova.igual("e a politica padrao passa a ALERTAR", PoliticaDlp.Acao.ALERTAR,
                PoliticaDlp.padrao().decidir(honesto).getAcao());
    System.out.println("   ..   " + PoliticaDlp.padrao().decidir(honesto).getMotivo());

    boolean recusou = false;
    try {
      new Varredura().varrerParcial(soONome, "   ");
    } catch (IllegalArgumentException e) {
      recusou = true;
    }
    Prova.certo("parcial SEM motivo escrito e' recusada (alerta injulgavel)", recusou);

    // Os dois motivos coexistem: capenga na entrada E estourou o teto interno.
    StringBuilder grande = new StringBuilder(soONome);
    while (grande.length() < 3000) {
      grande.append("texto administrativo comum. ");
    }
    ResultadoVarredura dois = new Varredura(500, 10_000L)
        .varrerParcial(grande.toString(), "extracao parcial do PDF");
    System.out.println("   ..   " + dois.getMotivoIncompleta());
    Prova.certo("motivo externo e motivo do teto se SOMAM, nenhum e' perdido",
                dois.getMotivoIncompleta().contains("extracao parcial do PDF")
                && dois.getMotivoIncompleta().contains("teto"));
  }

  /**
   * A trava contra a exfiltracao por tamanho: empurrar o dado sensivel para
   * depois do teto NAO produz um laudo de documento limpo.
   */
  private static void documentoGrandeNaoViraLimpo() {
    Prova.secao("Motor — documento maior que o teto sai marcado como NAO VARRIDO");

    StringBuilder enchimento = new StringBuilder();
    while (enchimento.length() < 5000) {
      enchimento.append("texto administrativo comum sem dado pessoal. ");
    }
    String documento = enchimento + "CPF escondido no fim: " + ProvaRegras.CPF_VALIDO_1;

    Varredura tetoBaixo = new Varredura(1000, 10_000L);
    ResultadoVarredura r = tetoBaixo.varrer(documento);

    Prova.certo("nao achou o CPF (esta' depois do teto)", r.getAchado("CPF") == null);
    Prova.certo("MAS a varredura esta' marcada como INCOMPLETA", !r.isCompleta());
    Prova.certo("e o motivo esta' escrito em portugues",
                r.getMotivoIncompleta() != null && r.getMotivoIncompleta().contains("teto"));
    System.out.println("   ..   motivo: " + r.getMotivoIncompleta());
    Prova.igual("varreu exatamente o teto de caracteres", 1000, r.getCaracteresVarridos());

    ResultadoVarredura completo = new Varredura().varrer(documento);
    Prova.certo("com o teto padrao, o mesmo documento e' varrido inteiro e o CPF aparece",
                completo.isCompleta() && completo.getAchado("CPF") != null);
  }
}
