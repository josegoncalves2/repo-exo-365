package br.pmo.dlp;

import java.util.Arrays;
import java.util.HashSet;

import br.pmo.dlp.PoliticaDlp.Acao;
import br.pmo.dlp.PoliticaDlp.Decisao;
import br.pmo.dlp.RegrasSensiveis.Severidade;

/** Prova da classificacao automatica e das tres travas da politica. */
final class ProvaPolitica {

  private ProvaPolitica() {
  }

  static void rodar() {
    classificacao();
    corteDeSeveridadeEVolume();
    incompletaNuncaLibera();
    isencaoPorRotulo();
    configuracaoErradaNaoQuebra();
  }

  private static void classificacao() {
    Prova.secao("Classificacao — derivada do conteudo");

    Varredura motor = new Varredura();
    Prova.igual("sem achado -> PUBLICO", Classificacao.PUBLICO,
                motor.varrer("Ata de reuniao ordinaria, sem anexos.").getClassificacao());
    Prova.igual("so' e-mail -> INTERNO", Classificacao.INTERNO,
                motor.varrer("Contato: gabinete@pmo.gov.br").getClassificacao());
    Prova.igual("segredo em texto claro -> RESTRITO", Classificacao.RESTRITO,
                motor.varrer("api_key: 9f2c1a4e7b").getClassificacao());
    Prova.igual("CPF -> SIGILOSO", Classificacao.SIGILOSO,
                motor.varrer("CPF " + ProvaRegras.CPF_VALIDO_1).getClassificacao());

    StringBuilder lista = new StringBuilder();
    for (int i = 0; i < Classificacao.VOLUME_QUE_ELEVA + 5; i++) {
      lista.append("servidor").append(i).append("@pmo.gov.br\n");
    }
    Prova.igual("muitos e-mails deixam de ser assinatura e viram cadastro -> RESTRITO",
                Classificacao.RESTRITO, motor.varrer(lista.toString()).getClassificacao());
  }

  private static void corteDeSeveridadeEVolume() {
    Prova.secao("Politica — corte por severidade e por volume");

    Varredura motor = new Varredura();
    ResultadoVarredura umCpf = motor.varrer("Requerente: CPF " + ProvaRegras.CPF_VALIDO_1);

    PoliticaDlp aPartirDeUm = new PoliticaDlp(Severidade.ALTA, 1, Acao.BLOQUEAR, Acao.ALERTAR, null);
    Prova.igual("com corte de 1 ocorrencia, um CPF ja' bloqueia",
                Acao.BLOQUEAR, aPartirDeUm.decidir(umCpf).getAcao());

    PoliticaDlp aPartirDeDez = new PoliticaDlp(Severidade.ALTA, 10, Acao.BLOQUEAR, Acao.ALERTAR, null);
    Decisao d = aPartirDeDez.decidir(umCpf);
    Prova.igual("com corte de 10, o CPF do requerente so' e' registrado",
                Acao.REGISTRAR, d.getAcao());
    Prova.certo("e o motivo explica o criterio", d.getMotivo().contains("10 ocorrencias"));

    StringBuilder cadastro = new StringBuilder();
    String[] cpfs = {"111.444.777-35", "529.982.247-25", "360.848.529-55"};
    for (int i = 0; i < 12; i++) {
      cadastro.append("linha ").append(i).append(": ").append(cpfs[i % 3]).append('\n');
    }
    ResultadoVarredura muitos = motor.varrer(cadastro.toString());
    System.out.println("   ..   " + muitos.resumo());
    Prova.certo("mas 12 CPFs no mesmo arquivo bloqueiam",
                aPartirDeDez.decidir(muitos).getAcao() == Acao.BLOQUEAR);

    PoliticaDlp soMedia = new PoliticaDlp(Severidade.MEDIA, 1, Acao.ALERTAR, Acao.ALERTAR, null);
    Prova.igual("com corte MEDIA, e-mail sozinho (BAIXA) nao dispara",
                Acao.REGISTRAR, soMedia.decidir(motor.varrer("a@b.gov.br")).getAcao());
  }

  /** A trava 1: documento nao varrido por inteiro nao pode passar por limpo. */
  private static void incompletaNuncaLibera() {
    Prova.secao("Politica — varredura incompleta NAO e' liberacao");

    StringBuilder grande = new StringBuilder();
    while (grande.length() < 3000) {
      grande.append("conteudo administrativo sem dado pessoal. ");
    }
    ResultadoVarredura parcial = new Varredura(500, 10_000L).varrer(grande.toString());

    Prova.certo("o laudo nao tem achado nenhum", parcial.isLimpo());
    Prova.certo("mas esta' marcado incompleto", !parcial.isCompleta());

    Decisao d = PoliticaDlp.padrao().decidir(parcial);
    Prova.igual("a politica padrao ALERTA em vez de liberar", Acao.ALERTAR, d.getAcao());
    Prova.certo("e diz por que, sem eufemismo",
                d.getMotivo().contains("NAO foi considerado livre"));
    System.out.println("   ..   " + d.getMotivo());

    Decisao nulo = PoliticaDlp.padrao().decidir(null);
    Prova.igual("laudo ausente tambem nao libera", Acao.ALERTAR, nulo.getAcao());

    PoliticaDlp dura = new PoliticaDlp(Severidade.ALTA, 1, Acao.ALERTAR, Acao.BLOQUEAR, null);
    Prova.igual("quem configurar BLOQUEAR para incompleta, bloqueia",
                Acao.BLOQUEAR, dura.decidir(parcial).getAcao());

    ResultadoVarredura parcialComCpf = new Varredura(200, 10_000L)
        .varrer("CPF " + ProvaRegras.CPF_VALIDO_1 + " no comeco. " + grande);
    Decisao mista = new PoliticaDlp(Severidade.ALTA, 1, Acao.BLOQUEAR, Acao.ALERTAR, null)
        .decidir(parcialComCpf);
    Prova.igual("achado grave + varredura parcial -> vence a acao MAIS dura",
                Acao.BLOQUEAR, mista.getAcao());
  }

  private static void isencaoPorRotulo() {
    Prova.secao("Politica — isencao e' por rotulo, nunca por pessoa");

    Varredura motor = new Varredura();
    ResultadoVarredura r = motor.varrer("Empresa contratada CNPJ " + ProvaRegras.CNPJ_VALIDO_1);

    PoliticaDlp semIsencao = new PoliticaDlp(Severidade.ALTA, 1, Acao.BLOQUEAR, Acao.ALERTAR, null);
    Prova.igual("sem isencao, CNPJ bloqueia", Acao.BLOQUEAR, semIsencao.decidir(r).getAcao());

    PoliticaDlp isentaCnpj = new PoliticaDlp(Severidade.ALTA, 1, Acao.BLOQUEAR, Acao.ALERTAR,
                                             new HashSet<>(Arrays.asList("cnpj")));
    Decisao d = isentaCnpj.decidir(r);
    Prova.igual("CNPJ isento (contrato publico e' publico) nao bloqueia",
                Acao.REGISTRAR, d.getAcao());
    Prova.certo("a isencao aceita minuscula e espaco na configuracao",
                isentaCnpj.getRotulosIsentos().contains("CNPJ"));

    ResultadoVarredura comCpf = motor.varrer("CNPJ " + ProvaRegras.CNPJ_VALIDO_1
                                             + " e CPF " + ProvaRegras.CPF_VALIDO_1);
    Prova.igual("mas isentar CNPJ nao isenta CPF", Acao.BLOQUEAR,
                isentaCnpj.decidir(comCpf).getAcao());
  }

  private static void configuracaoErradaNaoQuebra() {
    Prova.secao("Politica — configuracao errada cai no padrao, nao no extremo");

    Prova.igual("severidade escrita errado -> padrao", Severidade.ALTA,
                RegrasSensiveis.severidadeDe("altissima", Severidade.ALTA));
    Prova.igual("severidade vazia -> padrao", Severidade.MEDIA,
                RegrasSensiveis.severidadeDe("   ", Severidade.MEDIA));
    Prova.igual("severidade em minuscula funciona", Severidade.BAIXA,
                RegrasSensiveis.severidadeDe("baixa", Severidade.ALTA));

    Prova.igual("acao escrita errado NAO vira BLOQUEAR", Acao.ALERTAR,
                Acao.de("bloqueiaTudoAgora", Acao.ALERTAR));
    Prova.igual("acao escrita errado NAO vira IGNORAR", Acao.ALERTAR,
                Acao.de(null, Acao.ALERTAR));
    Prova.igual("acao valida em minuscula funciona", Acao.QUARENTENAR,
                Acao.de(" quarentenar ", Acao.ALERTAR));

    Prova.igual("classificacao invalida -> padrao", Classificacao.INTERNO,
                Classificacao.de("supersecreto", Classificacao.INTERNO));

    boolean recusou = false;
    try {
      new PoliticaDlp(Severidade.ALTA, 0, Acao.ALERTAR, Acao.ALERTAR, null);
    } catch (IllegalArgumentException e) {
      recusou = true;
    }
    Prova.certo("minimo de ocorrencias 0 e' recusado na construcao", recusou);
  }
}
