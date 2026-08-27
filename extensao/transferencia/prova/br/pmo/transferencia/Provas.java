package br.pmo.transferencia;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import br.pmo.mfa.Zona;
import br.pmo.transferencia.PoliticaTransferencia.Decisao;
import br.pmo.transferencia.PoliticaTransferencia.Modo;
import br.pmo.transferencia.Regra.Efeito;

/**
 * Provas da politica de transferencia. Sem JUnit: so' javac e java.
 * Codigo de saida != 0 aborta o empacotamento.
 */
public final class Provas {

  private static int ok = 0;
  private static int falhas = 0;

  private static void checa(String nome, boolean condicao) {
    if (condicao) {
      ok++;
      System.out.println("   ok   " + nome);
    } else {
      falhas++;
      System.out.println("  FALHOU " + nome);
    }
  }

  private static void secao(String titulo) {
    System.out.println();
    System.out.println("== " + titulo);
  }

  private static Set<String> grupos(String... valores) {
    return new LinkedHashSet<>(Arrays.asList(valores));
  }

  private static Pedido baixar(String usuario, Set<String> grupos, String arquivo,
                               long bytes, String origem) {
    return new Pedido(usuario, grupos, "/portal/download", arquivo, bytes, origem,
                      Pedido.Operacao.BAIXAR);
  }

  public static void main(String[] args) {
    provaExtensao();
    provaCondicoesCombinam();
    provaExcecaoAntesDaProibicao();
    provaExclusaoDeGrupo();
    provaZona();
    provaAcaoPadrao();
    provaModoObservacao();
    provaEstadoInerte();

    System.out.println();
    System.out.println("RESULTADO: " + (ok + falhas) + " asseveracoes, " + falhas + " falhas.");
    System.exit(falhas == 0 ? 0 : 1);
  }

  private static void provaExtensao() {
    secao("Extensao do arquivo");
    checa("relatorio.xlsx -> xlsx",
          "xlsx".equals(baixar("a", grupos(), "relatorio.xlsx", 1, "1.1.1.1").getExtensao()));
    checa("relatorio.2026.xlsx usa o ultimo ponto",
          "xlsx".equals(baixar("a", grupos(), "relatorio.2026.xlsx", 1, "1.1.1.1").getExtensao()));
    checa("MAIUSCULA.PDF vira minuscula",
          "pdf".equals(baixar("a", grupos(), "MAIUSCULA.PDF", 1, "1.1.1.1").getExtensao()));
    checa("sem extensao devolve vazio",
          "".equals(baixar("a", grupos(), "LEIAME", 1, "1.1.1.1").getExtensao()));
    checa("ponto no fim devolve vazio",
          "".equals(baixar("a", grupos(), "arquivo.", 1, "1.1.1.1").getExtensao()));
    checa(".gitignore nao e' extensao 'gitignore'",
          "".equals(baixar("a", grupos(), ".gitignore", 1, "1.1.1.1").getExtensao()));
    checa("nome nulo devolve vazio",
          "".equals(baixar("a", grupos(), null, 1, "1.1.1.1").getExtensao()));
  }

  private static void provaCondicoesCombinam() {
    secao("Condicoes sao E entre si, OU dentro de cada uma");

    Regra r = Regra.nomeada("pst-fora-da-rede", Efeito.NEGAR)
                   .paraExtensoes(".pst", "ost")
                   .paraGrupos("/platform/users")
                   .construir();

    checa("casa extensao pst e grupo certo",
          r.casa(baixar("joao", grupos("/platform/users"), "caixa.pst", 10, "1.1.1.1")));
    checa("casa a segunda extensao da lista (OU dentro da condicao)",
          r.casa(baixar("joao", grupos("/platform/users"), "caixa.ost", 10, "1.1.1.1")));
    checa("NAO casa extensao diferente (E entre condicoes)",
          !r.casa(baixar("joao", grupos("/platform/users"), "nota.pdf", 10, "1.1.1.1")));
    checa("NAO casa grupo diferente",
          !r.casa(baixar("joao", grupos("/platform/administrators"), "caixa.pst", 10, "1.1.1.1")));

    Regra semCondicao = Regra.nomeada("fecho", Efeito.NEGAR).construir();
    checa("regra sem condicao casa com qualquer pedido",
          semCondicao.casa(baixar("x", grupos(), "q.txt", 1, "9.9.9.9")));

    Regra porTamanho = Regra.nomeada("grandes", Efeito.NEGAR)
                            .aPartirDeBytes(1024 * 1024)
                            .construir();
    checa("tamanho acima do minimo casa",
          porTamanho.casa(baixar("x", grupos(), "g.zip", 2 * 1024 * 1024, "1.1.1.1")));
    checa("tamanho abaixo do minimo NAO casa",
          !porTamanho.casa(baixar("x", grupos(), "p.zip", 1024, "1.1.1.1")));

    Regra soCompartilhar = Regra.nomeada("share", Efeito.NEGAR)
                                .paraOperacoes(Pedido.Operacao.COMPARTILHAR)
                                .construir();
    checa("operacao diferente NAO casa",
          !soCompartilhar.casa(baixar("x", grupos(), "a.txt", 1, "1.1.1.1")));
  }

  private static void provaExcecaoAntesDaProibicao() {
    secao("Primeira-que-casa: a excecao tem de vir antes da proibicao");

    Regra excecao = Regra.nomeada("ti-pode-pst", Efeito.PERMITIR)
                         .paraExtensoes("pst")
                         .paraGrupos("/platform/ti")
                         .comMotivo("TI precisa de .pst para migracao de caixa")
                         .construir();
    Regra proibicao = Regra.nomeada("ninguem-baixa-pst", Efeito.NEGAR)
                           .paraExtensoes("pst")
                           .comMotivo("arquivo de caixa postal nao sai do portal")
                           .construir();

    PoliticaTransferencia certa = new PoliticaTransferencia(
        Arrays.asList(excecao, proibicao), Efeito.PERMITIR, Modo.APLICACAO);

    Decisao ti = certa.decidir(baixar("ana", grupos("/platform/ti"), "caixa.pst", 10, "1.1.1.1"));
    checa("TI baixa .pst (excecao primeiro)", ti.isPermitido());
    checa("e a regra responsavel tem nome", "ti-pode-pst".equals(ti.getRegra()));

    Decisao outro = certa.decidir(
        baixar("joao", grupos("/platform/users"), "caixa.pst", 10, "1.1.1.1"));
    checa("nao-TI e' negado", !outro.isPermitido());
    checa("e o motivo e' o da regra", outro.getMotivo().contains("caixa postal"));

    // Ordem invertida: a proibicao ampla engole a excecao.
    PoliticaTransferencia invertida = new PoliticaTransferencia(
        Arrays.asList(proibicao, excecao), Efeito.PERMITIR, Modo.APLICACAO);
    checa("ordem invertida faz a excecao nunca se aplicar (por isso a ordem importa)",
          !invertida.decidir(baixar("ana", grupos("/platform/ti"), "caixa.pst", 10, "1.1.1.1"))
                    .isPermitido());
  }

  private static void provaExclusaoDeGrupo() {
    secao("Exclusao de grupo: 'todos menos X'");

    Regra r = Regra.nomeada("todos-menos-ti", Efeito.NEGAR)
                   .paraExtensoes("pst")
                   .excetoGrupos("/platform/ti")
                   .construir();

    checa("usuario comum casa (e sera' negado)",
          r.casa(baixar("joao", grupos("/platform/users"), "c.pst", 1, "1.1.1.1")));
    checa("membro de TI NAO casa",
          !r.casa(baixar("ana", grupos("/platform/ti"), "c.pst", 1, "1.1.1.1")));
    checa("quem esta' em TI E em outro grupo tambem NAO casa (exclusao vem primeiro)",
          !r.casa(baixar("ana", grupos("/platform/users", "/platform/ti"), "c.pst", 1, "1.1.1.1")));
  }

  private static void provaZona() {
    secao("Regra por zona de rede");

    List<Zona> interna = Collections.singletonList(Zona.de("192.168.1.0/24"));
    Regra r = Regra.nomeada("fora-da-rede-nao-baixa", Efeito.NEGAR)
                   .paraZonas(interna)
                   .construir();

    checa("origem na zona casa", r.casa(baixar("x", grupos(), "a.pdf", 1, "192.168.1.50")));
    checa("origem fora da zona NAO casa", !r.casa(baixar("x", grupos(), "a.pdf", 1, "8.8.8.8")));
    checa("origem desconhecida NAO casa regra de rede",
          !r.casa(baixar("x", grupos(), "a.pdf", 1, null)));
    checa("mascara quebrada funciona (/26)",
          Regra.nomeada("z", Efeito.NEGAR)
               .paraZonas(Collections.singletonList(Zona.de("192.168.1.64/26")))
               .construir()
               .casa(baixar("x", grupos(), "a.pdf", 1, "192.168.1.100")));
  }

  private static void provaAcaoPadrao() {
    secao("Acao padrao quando nenhuma regra casa");

    PoliticaTransferencia permissiva = new PoliticaTransferencia(
        Collections.<Regra>emptyList(), Efeito.PERMITIR, Modo.APLICACAO);
    Decisao d1 = permissiva.decidir(baixar("x", grupos(), "a.pdf", 1, "1.1.1.1"));
    checa("padrao PERMITIR libera", d1.isPermitido());
    checa("e nomeia a acao padrao como responsavel", d1.getRegra().contains("padrao"));

    PoliticaTransferencia restritiva = new PoliticaTransferencia(
        Collections.<Regra>emptyList(), Efeito.NEGAR, Modo.APLICACAO);
    checa("padrao NEGAR bloqueia",
          !restritiva.decidir(baixar("x", grupos(), "a.pdf", 1, "1.1.1.1")).isPermitido());
  }

  private static void provaModoObservacao() {
    secao("Modo observacao: avalia e registra, mas NAO impede");

    Regra nega = Regra.nomeada("nega-tudo", Efeito.NEGAR).construir();

    PoliticaTransferencia observando = new PoliticaTransferencia(
        Collections.singletonList(nega), Efeito.PERMITIR, Modo.OBSERVACAO);
    Decisao obs = observando.decidir(baixar("x", grupos(), "a.pdf", 1, "1.1.1.1"));
    checa("em observacao a decisao continua sendo NEGADO", !obs.isPermitido());
    checa("mas NAO impede de fato", !obs.impedeDeFato());

    PoliticaTransferencia aplicando = new PoliticaTransferencia(
        Collections.singletonList(nega), Efeito.PERMITIR, Modo.APLICACAO);
    Decisao apl = aplicando.decidir(baixar("x", grupos(), "a.pdf", 1, "1.1.1.1"));
    checa("em aplicacao impede de fato", apl.impedeDeFato());

    checa("permitido nunca impede, em qualquer modo",
          !new PoliticaTransferencia(
               Collections.singletonList(Regra.nomeada("ok", Efeito.PERMITIR).construir()),
               Efeito.NEGAR, Modo.APLICACAO)
               .decidir(baixar("x", grupos(), "a.pdf", 1, "1.1.1.1"))
               .impedeDeFato());

    checa("modo invalido cai no padrao, nao em bloqueio",
          Modo.de("BANANA", Modo.OBSERVACAO) == Modo.OBSERVACAO);
  }

  private static void provaEstadoInerte() {
    secao("Estado inerte");
    PoliticaTransferencia vazia = new PoliticaTransferencia(
        Collections.<Regra>emptyList(), Efeito.PERMITIR, Modo.APLICACAO);
    checa("sem regra e com padrao PERMITIR, a politica se declara inerte",
          vazia.estaInerte());

    PoliticaTransferencia comRegra = new PoliticaTransferencia(
        Collections.singletonList(Regra.nomeada("r", Efeito.NEGAR).construir()),
        Efeito.PERMITIR, Modo.APLICACAO);
    checa("com regra NAO esta' inerte", !comRegra.estaInerte());

    boolean lancou = false;
    try {
      Regra.nomeada("", Efeito.NEGAR);
    } catch (IllegalArgumentException e) {
      lancou = true;
    }
    checa("regra sem nome lanca (o nome vai para a auditoria)", lancou);
  }
}
