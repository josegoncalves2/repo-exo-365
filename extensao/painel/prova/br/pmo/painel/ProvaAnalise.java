package br.pmo.painel;

import java.util.ArrayList;
import java.util.List;

import br.pmo.dlp.CategoriaConformidade;
import br.pmo.dlp.Classificacao;
import br.pmo.dlp.InstantaneoConformidade;
import br.pmo.dlp.MotivoNaoVarrido;
import br.pmo.dlp.PoliticaDlp;
import br.pmo.dlp.RelatorioConformidade;
import br.pmo.dlp.Varredura;

/**
 * Provas de {@link AnaliseAoVivo}: a ferramenta que prova, na tela, que o motor
 * existe.
 *
 * <p>Nenhum numero abaixo pertence a pessoa real -- todos foram gerados pelo
 * algoritmo publico de digito verificador.
 */
public final class ProvaAnalise {

  /** CPFs validos pelo modulo 11, usados so' como entrada de teste. */
  private static final String CPF_A = "111.444.777-35";

  private static final String CPF_B = "529.982.247-25";

  private ProvaAnalise() {
  }

  private static AnaliseAoVivo montar(RelatorioConformidade relatorio, int teto) {
    return new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), relatorio, teto);
  }

  public static void rodar() {
    caixaEmBranco();
    achaCpfDeVerdade();
    nadaEmClaroEscapa();
    referenciaNaoEOTexto();
    truncarNaoEhLimpo();
    itensEOcorrenciasSaoDiferentes();
    construcaoInvalida();
  }

  private static void caixaEmBranco() {
    Prova.secao("AnaliseAoVivo — caixa em branco nao vira item do relatorio");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise = montar(relatorio, 1000);

    ResumoAnalise vazio = analise.analisar("");
    Prova.certo("resumo marcado como vazio", vazio.isVazio());
    Prova.certo("e NAO como falha (sao desfechos diferentes)", !vazio.isFalhou());
    Prova.certo("e NAO como limpo -- nao analisou nada", !vazio.isLimpo());
    // Contar clique em botao como item inflaria a coluna "Limpo", que e'
    // justamente o numero que este relatorio existe para manter honesto.
    Prova.igual("nada foi registrado no relatorio", 0, relatorio.getTotal());

    ResumoAnalise nulo = analise.analisar(null);
    Prova.certo("nulo tambem e' vazio", nulo.isVazio());
    Prova.igual("e tambem nao registra", 0, relatorio.getTotal());
  }

  private static void achaCpfDeVerdade() {
    Prova.secao("AnaliseAoVivo — o motor de verdade responde na tela");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise = montar(relatorio, 100000);

    ResumoAnalise r = analise.analisar("Requerente inscrito no CPF " + CPF_A + ".");
    Prova.certo("nao falhou", !r.isFalhou());
    Prova.certo("nao esta' vazio", !r.isVazio());
    Prova.certo("NAO e' limpo -- havia um CPF ali", !r.isLimpo());
    Prova.certo("a varredura foi completa", r.isCompleta());
    Prova.igual("um unico rotulo achado", 1, r.getAchados().size());
    Prova.igual("e o rotulo e' CPF", "CPF", r.getAchados().get(0).getRotulo());
    Prova.igual("severidade ALTA", "ALTA", r.getAchados().get(0).getSeveridade());
    Prova.igual("uma ocorrencia", 1, r.getAchados().get(0).getQuantidade());
    Prova.igual("classificacao derivada e' SIGILOSO",
                Classificacao.SIGILOSO.name(), r.getClassificacao());
    Prova.igual("a politica padrao decide ALERTAR",
                PoliticaDlp.Acao.ALERTAR.name(), r.getAcao());
    Prova.certo("o CPF aparece entre os rotulos que dispararam",
                r.getRotulosGatilho().contains("CPF"));
    Prova.certo("ha' amostra mascarada para o administrador conferir",
                !r.getAchados().get(0).getAmostrasMascaradas().isEmpty());
    Prova.igual("o relatorio contabilizou o item", 1, relatorio.getTotal());
    Prova.igual("na categoria ACHADO", 1,
                relatorio.instantaneo().getQuantidade(CategoriaConformidade.ACHADO));
  }

  private static void nadaEmClaroEscapa() {
    Prova.secao("AnaliseAoVivo — nenhum valor em claro sobrevive ao resumo");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise = montar(relatorio, 100000);
    String texto = "Servidor " + CPF_A + " e conjuge " + CPF_B
                   + ", contato fulano.silva@prefeitura.gov.br";
    ResumoAnalise r = analise.analisar(texto);

    // Varre TODO campo textual alcancavel a partir do resumo. E' a asseveracao
    // que sustenta a promessa de ResumoAnalise: a garantia nao e' "lembrar de
    // nao imprimir", e' nao haver o que imprimir.
    String tudo = despejar(r);
    Prova.certo("o CPF A nao esta' em lugar nenhum do resumo", !tudo.contains(CPF_A));
    Prova.certo("o CPF B nao esta' em lugar nenhum do resumo", !tudo.contains(CPF_B));
    Prova.certo("nem os primeiros digitos do CPF A", !tudo.contains("111.444"));
    Prova.certo("nem o texto colado inteiro", !tudo.contains(texto));
    Prova.certo("o e-mail em claro nao sobrevive",
                !tudo.contains("fulano.silva@prefeitura.gov.br"));
    // ... e ainda assim ha' o que mostrar: o resumo nao ficou seguro por ficar vazio.
    Prova.certo("mas o resumo TEM achados (nao ficou seguro por estar vazio)",
                !r.getAchados().isEmpty());
    Prova.certo("e tem amostra mascarada visivel", tudo.contains("*"));
  }

  private static void referenciaNaoEOTexto() {
    Prova.secao("AnaliseAoVivo — a referencia registrada nao carrega o conteudo");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise = montar(relatorio, 100000);
    analise.analisar("CPF " + CPF_A);

    InstantaneoConformidade i = relatorio.instantaneo();
    List<String> amostras = new ArrayList<>(i.getAmostras(CategoriaConformidade.ACHADO));
    Prova.igual("ha' exatamente uma referencia guardada", 1, amostras.size());
    // O relatorio imprime estas referencias, e o CSV circula por e-mail. Se a
    // referencia fosse o texto, o relatorio de vazamento seria o vazamento.
    Prova.certo("a referencia e' sintetica",
                amostras.get(0).startsWith(AnaliseAoVivo.PREFIXO_REFERENCIA));
    Prova.certo("a referencia NAO contem o CPF analisado",
                !amostras.get(0).contains("111.444.777-35"));
    Prova.certo("o CSV do relatorio nao contem o CPF analisado",
                !i.emCsv().contains(CPF_A));
    Prova.certo("o relatorio em texto tambem nao", !i.emTexto().contains(CPF_A));

    analise.analisar("CPF " + CPF_B);
    List<String> duas = relatorio.instantaneo().getAmostras(CategoriaConformidade.ACHADO);
    Prova.igual("duas analises geram duas referencias", 2, duas.size());
    Prova.certo("e elas sao distintas (o contador anda)", !duas.get(0).equals(duas.get(1)));
  }

  private static void truncarNaoEhLimpo() {
    Prova.secao("AnaliseAoVivo — estourar o teto NAO produz documento limpo");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    // Teto minusculo, e o dado sensivel DEPOIS do corte: e' o caminho trivial de
    // exfiltracao que a categoria NAO_VARRIDO existe para fechar.
    AnaliseAoVivo analise = montar(relatorio, 20);
    ResumoAnalise r = analise.analisar("texto inofensivo de enchimento e depois o CPF "
                                       + CPF_A);

    Prova.certo("o painel marcou que cortou", r.isTruncadoPeloPainel());
    Prova.certo("a varredura NAO e' completa", !r.isCompleta());
    Prova.certo("ha' motivo escrito para o alerta ser julgavel",
                r.getMotivoIncompleta() != null && !r.getMotivoIncompleta().isEmpty());
    Prova.certo("o resumo NAO se declara limpo", !r.isLimpo());
    Prova.igual("o tamanho original foi preservado",
                "texto inofensivo de enchimento e depois o CPF ".length() + CPF_A.length(),
                r.getTamanhoOriginal());
    Prova.igual("so' o teto foi analisado", 20, r.getCaracteresVarridos());

    InstantaneoConformidade i = relatorio.instantaneo();
    Prova.igual("o relatorio conta como NAO VARRIDO, nao como LIMPO", 1,
                i.getQuantidade(CategoriaConformidade.NAO_VARRIDO));
    Prova.igual("e nada foi para a coluna Limpo", 0,
                i.getQuantidade(CategoriaConformidade.LIMPO));
    // O texto do motivo que este painel escreve tem de cair na gaveta certa:
    // "revisar configuracao" e nao "comprar OCR". Sao orcamentos diferentes.
    Prova.igual("o motivo cai na gaveta de teto de caracteres", 1,
                i.getQuantidade(MotivoNaoVarrido.ACIMA_DO_TETO_DE_CARACTERES));
    Prova.igual("e nao na de provavel digitalizacao", 0,
                i.getQuantidade(MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO));
    Prova.igual("nao varrido nao recebe classificacao PUBLICO", 0,
                i.getQuantidade(Classificacao.PUBLICO));

    Prova.certo("a politica nao trata incompleto como liberacao",
                !PoliticaDlp.Acao.IGNORAR.name().equals(r.getAcao()));
  }

  private static void itensEOcorrenciasSaoDiferentes() {
    Prova.secao("AnaliseAoVivo — itens por tipo e ocorrencias por tipo divergem");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    AnaliseAoVivo analise = montar(relatorio, 100000);

    // Um item com DOIS CPFs, e depois outro item com UM. Se a tela mostrasse so'
    // um dos dois numeros, "2 itens" e "3 ocorrencias" seriam indistinguiveis --
    // e levam a decisoes opostas (tratar arquivo x treinar pessoas).
    analise.analisar("Folha: " + CPF_A + " e " + CPF_B);
    analise.analisar("Oficio do requerente " + CPF_A);

    InstantaneoConformidade i = relatorio.instantaneo();
    Prova.igual("dois itens contem CPF", Integer.valueOf(2),
                i.getItensPorRotulo().get("CPF"));
    Prova.igual("mas ha' tres ocorrencias de CPF", Long.valueOf(3L),
                i.getOcorrenciasPorRotulo().get("CPF"));
    Prova.certo("os dois numeros sao mesmo diferentes nesta amostra",
                !i.getItensPorRotulo().get("CPF").toString()
                  .equals(i.getOcorrenciasPorRotulo().get("CPF").toString()));
  }

  private static void construcaoInvalida() {
    Prova.secao("AnaliseAoVivo — configuracao invalida impede a tela de subir");
    RelatorioConformidade relatorio = new RelatorioConformidade("prova");
    Prova.recusa("sem motor e' recusado",
                 () -> new AnaliseAoVivo(null, PoliticaDlp.padrao(), relatorio, 10));
    Prova.recusa("sem politica e' recusado",
                 () -> new AnaliseAoVivo(new Varredura(), null, relatorio, 10));
    Prova.recusa("sem relatorio e' recusado",
                 () -> new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), null, 10));
    Prova.recusa("teto zero e' recusado",
                 () -> new AnaliseAoVivo(new Varredura(), PoliticaDlp.padrao(), relatorio, 0));
  }

  /** Concatena todo texto alcancavel no resumo, para a busca por valor em claro. */
  private static String despejar(ResumoAnalise r) {
    StringBuilder sb = new StringBuilder();
    sb.append(r.getErro()).append('\n')
      .append(r.getMotivoIncompleta()).append('\n')
      .append(r.getClassificacao()).append('\n')
      .append(r.getAcao()).append('\n')
      .append(r.getMotivoDecisao()).append('\n');
    for (String g : r.getRotulosGatilho()) {
      sb.append(g).append('\n');
    }
    for (ResumoAnalise.AchadoSeguro a : r.getAchados()) {
      sb.append(a.getRotulo()).append('\n').append(a.getSeveridade()).append('\n');
      for (String amostra : a.getAmostrasMascaradas()) {
        sb.append(amostra).append('\n');
      }
    }
    return sb.toString();
  }
}
