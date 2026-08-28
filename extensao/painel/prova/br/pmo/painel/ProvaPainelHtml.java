package br.pmo.painel;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import br.pmo.dlp.CategoriaConformidade;
import br.pmo.dlp.Classificacao;
import br.pmo.dlp.InstantaneoConformidade;
import br.pmo.dlp.MotivoNaoVarrido;
import br.pmo.dlp.PoliticaDlp;
import br.pmo.dlp.RegrasSensiveis;
import br.pmo.dlp.RelatorioConformidade;
import br.pmo.dlp.Varredura;

/**
 * Provas de {@link PainelHtml}.
 *
 * <p>Duas familias de asseveracao, e as duas falham por motivo proprio:
 *
 * <ol>
 *   <li><b>Idioma.</b> Carrega o arquivo {@code .properties} DE VERDADE, o mesmo
 *       que vai dentro do WAR, e monta a tela inteira com ele. Toda chave que
 *       faltar aparece como {@code !chave!} no HTML -- entao procurar por
 *       {@code !painel.} prova a cobertura sem precisar manter uma lista de
 *       chaves que envelheceria em silencio;</li>
 *   <li><b>XSS.</b> Empurra vetor de script por cada porta de entrada de texto
 *       de terceiro que a tela tem, e confere que nao sai tag executavel.</li>
 * </ol>
 */
public final class ProvaPainelHtml {

  private static final String CPF = "111.444.777-35";

  private ProvaPainelHtml() {
  }

  public static void rodar() {
    idiomaCompleto();
    numerosNaTela();
    xssNaCaixaDeTexto();
    xssNoMotivoCru();
    acessoNegado();
    truncadoNaoDizLimpo();
    percentualEstavel();
  }

  private static void truncadoNaoDizLimpo() {
    Prova.secao("PainelHtml — texto cortado no teto nao e' anunciado como limpo");
    Rotulos pt = rotulos(new Locale("pt", "BR"));
    RelatorioConformidade rel = new RelatorioConformidade("prova");
    // Teto minusculo e o CPF DEPOIS do corte: a tela nao pode dizer "nada
    // encontrado" sobre um trecho que ela nem chegou a ler.
    AnaliseAoVivo analise =
        new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), rel, 20);
    ResumoAnalise r = analise.analisar("texto de enchimento aqui e entao o CPF " + CPF);
    String html = PainelHtml.pagina(rel.instantaneo(), r, "/a", "p_", "t", "c.csv", 20, pt);

    Prova.certo("o resumo nao se declara limpo", !r.isLimpo());
    Prova.certo("a frase 'nada encontrado' NAO aparece",
                !html.contains(pt.de(PainelHtml.CH_ANALISE_LIMPO)));
    Prova.certo("mas o aviso de corte aparece",
                html.contains(pt.de(PainelHtml.CH_ANALISE_INCOMPLETA)));

    // O contraste: um texto realmente limpo, lido por inteiro, DEVE receber a
    // frase. Sem esta metade, a asseveracao acima passaria com a frase removida.
    RelatorioConformidade rel2 = new RelatorioConformidade("prova");
    AnaliseAoVivo analise2 =
        new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), rel2, 100000);
    ResumoAnalise limpo = analise2.analisar("Oficio comum, sem dado pessoal algum.");
    String html2 = PainelHtml.pagina(rel2.instantaneo(), limpo, "/a", "p_", "t", "c.csv",
                                     100000, pt);
    Prova.certo("texto realmente limpo e' declarado limpo", limpo.isLimpo());
    Prova.certo("e a frase aparece para ele",
                html2.contains(pt.de(PainelHtml.CH_ANALISE_LIMPO)));
  }

  private static Rotulos rotulos(Locale locale) {
    return new Rotulos(ResourceBundle.getBundle(Rotulos.NOME_BASE, locale));
  }

  private static InstantaneoConformidade comDados() {
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise =
        new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), relatorio, 100000);
    analise.analisar("Folha com " + CPF + " e 529.982.247-25");
    analise.analisar("Oficio limpo, sem nada de interesse.");
    // Um item que nao pode ser lido por inteiro, para a secao de motivos existir.
    relatorio.registrar("digitalizacao-001",
                        new Varredura().varrerParcial("", "nenhum extrator leu o binario"),
                        null);
    return relatorio.instantaneo();
  }

  private static String telaCompleta(Rotulos r) {
    return PainelHtml.pagina(comDados(), null, "/acao", "pmo_", "texto",
                             "conformidade.csv", 50000, r);
  }

  private static void idiomaCompleto() {
    Prova.secao("PainelHtml — o arquivo de idioma cobre a tela inteira");
    Rotulos pt;
    try {
      pt = rotulos(new Locale("pt", "BR"));
    } catch (MissingResourceException e) {
      Prova.certo("o arquivo de idioma pt_BR foi encontrado no classpath", false);
      return;
    }
    Prova.certo("o arquivo de idioma pt_BR foi encontrado no classpath", true);

    String html = telaCompleta(pt);
    // Rotulos.de devolve !chave! quando falta. Se sobrou algum, falta traducao.
    Prova.certo("nenhuma chave faltando na tela do relatorio (sem marcador !painel.)",
                !html.contains("!painel."));

    // A tela do resultado da analise so' aparece depois de um POST, entao e'
    // montada a parte -- senao as chaves dela nunca seriam exercitadas.
    RelatorioConformidade rel = new RelatorioConformidade("prova");
    AnaliseAoVivo analise =
        new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), rel, 100000);
    String comResultado = PainelHtml.pagina(rel.instantaneo(),
                                            analise.analisar("CPF " + CPF),
                                            "/acao", "pmo_", "texto", "c.csv", 50000, pt);
    Prova.certo("nenhuma chave faltando na tela do resultado da analise",
                !comResultado.contains("!painel."));

    // Cada gaveta de enum tem rotulo e encaminhamento proprios: sao eles que
    // separam "mudar uma linha de configuracao" de "abrir processo de compra".
    boolean todosMotivos = true;
    for (MotivoNaoVarrido m : MotivoNaoVarrido.values()) {
      todosMotivos &= pt.tem(PainelHtml.PRE_MOTIVO + m.name())
                   && pt.tem(PainelHtml.PRE_ENCAMINHAMENTO + m.name());
    }
    Prova.certo("todo motivo de nao-varrido tem rotulo E encaminhamento em pt_BR",
                todosMotivos);
    boolean todasCategorias = true;
    for (CategoriaConformidade c : CategoriaConformidade.values()) {
      todasCategorias &= pt.tem(PainelHtml.PRE_CATEGORIA + c.name());
    }
    Prova.certo("as tres categorias tem rotulo em pt_BR", todasCategorias);
    boolean todasClassificacoes = true;
    for (Classificacao c : Classificacao.values()) {
      todasClassificacoes &= pt.tem(PainelHtml.PRE_CLASSIFICACAO + c.name());
    }
    Prova.certo("as quatro classificacoes tem rotulo em pt_BR", todasClassificacoes);
    boolean todasAcoes = true;
    for (PoliticaDlp.Acao a : PoliticaDlp.Acao.values()) {
      todasAcoes &= pt.tem(PainelHtml.PRE_ACAO + a.name());
    }
    Prova.certo("as seis acoes de politica tem rotulo em pt_BR", todasAcoes);
    boolean todasSeveridades = true;
    for (RegrasSensiveis.Severidade s : RegrasSensiveis.Severidade.values()) {
      todasSeveridades &= pt.tem(PainelHtml.PRE_SEVERIDADE + s.name());
    }
    Prova.certo("as tres severidades tem rotulo em pt_BR", todasSeveridades);

    Prova.certo("o ingles tambem cobre a tela",
                !telaCompleta(rotulos(Locale.ENGLISH)).contains("!painel."));

    // Prova que a prova nao passa por acidente: com um pacote ausente, o
    // marcador TEM de aparecer. Se nao aparecesse, a asseveracao acima estaria
    // verificando o nada.
    Prova.certo("sem arquivo de idioma, o marcador !painel. de fato aparece",
                telaCompleta(new Rotulos(null)).contains("!painel."));
  }

  private static void numerosNaTela() {
    Prova.secao("PainelHtml — os numeros exigidos aparecem");
    Rotulos pt = rotulos(new Locale("pt", "BR"));
    InstantaneoConformidade i = comDados();
    String html = PainelHtml.pagina(i, null, "/acao", "pmo_", "texto", "c.csv", 50000, pt);

    Prova.certo("as tres categorias estao na tela",
                html.contains("pmoCatLIMPO") && html.contains("pmoCatACHADO")
                && html.contains("pmoCatNAO_VARRIDO"));
    Prova.certo("ha' percentual formatado com duas casas", html.contains("%"));
    Prova.certo("a secao de motivos aparece quando ha' nao-varrido",
                html.contains(pt.de(PainelHtml.CH_MOTIVOS)));
    Prova.certo("o encaminhamento do motivo aparece ao lado do numero",
                html.contains(pt.de(PainelHtml.PRE_ENCAMINHAMENTO
                                    + MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO.name())));
    Prova.certo("a coluna Itens aparece", html.contains(pt.de(PainelHtml.CH_COL_ITENS)));
    Prova.certo("a coluna Ocorrencias aparece",
                html.contains(pt.de(PainelHtml.CH_COL_OCORRENCIAS)));
    Prova.certo("a nota que explica a diferenca entre as duas aparece",
                html.contains(pt.de(PainelHtml.CH_TIPOS_NOTA).substring(0, 20)));
    Prova.certo("a secao de classificacao aparece",
                html.contains(pt.de(PainelHtml.CH_CLASSIFICACAO)));
    Prova.certo("o botao de CSV aparece com nome de arquivo",
                html.contains("data-pmo-arquivo=\"c.csv\""));
    Prova.certo("o CSV vai embutido no botao", html.contains("data-pmo-csv=\""));
    Prova.certo("o formulario posta para a URL de acao recebida",
                html.contains("action=\"/acao\""));
    Prova.certo("a caixa de texto usa o nome de campo recebido",
                html.contains("name=\"texto\""));
    Prova.certo("o teto vai para o maxlength do navegador",
                html.contains("maxlength=\"50000\""));
    Prova.certo("os id sao prefixados para nao colidir com outro portlet",
                html.contains("id=\"pmo_texto\""));
  }

  private static void xssNaCaixaDeTexto() {
    Prova.secao("PainelHtml — script vindo da caixa de texto nao sobrevive");
    Rotulos pt = rotulos(new Locale("pt", "BR"));
    RelatorioConformidade rel = new RelatorioConformidade("prova");
    AnaliseAoVivo analise =
        new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), rel, 100000);

    String ataque = "<script>alert(document.cookie)</script>"
                    + " <img src=x onerror=alert(1)> CPF " + CPF;
    ResumoAnalise r = analise.analisar(ataque);
    String html = PainelHtml.pagina(rel.instantaneo(), r, "/acao", "pmo_", "texto",
                                    "c.csv", 50000, pt);

    Prova.certo("nao ha' tag <script> executavel no HTML",
                !html.toLowerCase(Locale.ROOT).contains("<script"));
    Prova.certo("nao ha' <img injetado", !html.toLowerCase(Locale.ROOT).contains("<img"));
    Prova.certo("nao ha' manipulador onerror=", !html.contains("onerror="));
    // A tela nao reexibe o texto enviado -- de proposito: ele pode conter
    // justamente o dado que o painel se recusa a mostrar em claro.
    Prova.certo("o texto enviado NAO e' reexibido na caixa", !html.contains(ataque));
    Prova.certo("e o CPF enviado nao aparece em claro em lugar nenhum",
                !html.contains(CPF));
    // ...mas a analise DEVE ter acontecido: se nada aparecesse, a tela estaria
    // segura por estar vazia, que nao e' o que se quer provar.
    Prova.certo("mesmo assim o CPF foi detectado e mostrado mascarado",
                html.contains("***"));
    Prova.certo("a caixa de texto continua vazia para a proxima analise",
                html.contains("class=\"pmoTexto\" maxlength=\"50000\"></textarea>"));
  }

  private static void xssNoMotivoCru() {
    Prova.secao("PainelHtml — script vindo do motivo cru nao sobrevive");
    // O motivo cru e' o caminho mais esquecido: nasce de nome de arquivo, isto
    // e', de quem fez o upload -- nao do administrador que abre a tela.
    RelatorioConformidade rel = new RelatorioConformidade("prova");
    rel.registrar("item-1",
                  new Varredura().varrerParcial("", "<script>alert('nome do arquivo')</script>"),
                  null);
    Rotulos pt = rotulos(new Locale("pt", "BR"));
    String html = PainelHtml.pagina(rel.instantaneo(), null, "/a", "p_", "t", "c.csv",
                                    1000, pt);
    Prova.certo("o motivo cru foi mesmo classificado como OUTRO (a porta existe)",
                rel.instantaneo().getQuantidade(MotivoNaoVarrido.OUTRO) == 1);
    Prova.certo("e ele aparece na tela (nao foi escondido)", html.contains("alert"));
    Prova.certo("mas sem tag executavel",
                !html.toLowerCase(Locale.ROOT).contains("<script"));
    Prova.certo("com os sinais de menor escapados", html.contains("&lt;script&gt;"));
  }

  private static void acessoNegado() {
    Prova.secao("PainelHtml — a recusa e' explicita");
    Rotulos pt = rotulos(new Locale("pt", "BR"));
    String html = PainelHtml.acessoNegado(pt, "/platform/administrators");
    Prova.certo("diz que o acesso foi negado",
                html.contains(pt.de(PainelHtml.CH_ACESSO_NEGADO)));
    Prova.certo("informa o grupo exigido", html.contains("/platform/administrators"));
    Prova.certo("nao vaza nenhum numero do relatorio", !html.contains("pmoTabela"));
    Prova.certo("nao traz a caixa de analise", !html.contains("<textarea"));
    Prova.certo("nenhuma chave faltando", !html.contains("!painel."));
  }

  private static void percentualEstavel() {
    Prova.secao("PainelHtml — percentual nao muda com o idioma do navegador");
    // Dois relatorios comparados lado a lado tem de ter o mesmo separador
    // decimal, senao a comparacao -- que e' o uso principal do numero -- falha.
    Prova.igual("usa ponto decimal, sempre", "12.50%", PainelHtml.percentual(12.5d));
    Prova.igual("duas casas mesmo quando redondo", "0.00%", PainelHtml.percentual(0d));
    Prova.igual("cem por cento", "100.00%", PainelHtml.percentual(100d));
  }
}
