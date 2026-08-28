package br.pmo.painel;

import java.util.ArrayList;
import java.util.List;

import br.pmo.dlp.Achado;
import br.pmo.dlp.MotivoNaoVarrido;
import br.pmo.dlp.PoliticaDlp;
import br.pmo.dlp.RelatorioConformidade;
import br.pmo.dlp.ResultadoVarredura;
import br.pmo.dlp.Varredura;

/**
 * A ferramenta "Analisar texto": pega o que o administrador colou, roda o motor
 * de verdade, decide pela politica de verdade e registra no relatorio de
 * verdade.
 *
 * <p><b>POR QUE ISTO PRECISA EXISTIR NUMA TELA.</b> Os tres jars da extensao
 * rodam onde ninguem ve: dentro do processamento de upload e de um job de
 * acervo. Ate' aqui a unica evidencia de que o DLP existe era linha de log. Esta
 * classe e' o caminho pelo qual o administrador aponta o motor para um texto que
 * ELE escolheu e ve o laudo na hora -- sem depender de haver documento no
 * acervo, sem depender de o job ter rodado, sem depender de nenhum estado
 * anterior. Se a tela responde, o motor existe; se nao responde, o motor esta'
 * quebrado. E' um teste de fumaca operado por quem tem de confiar no sistema.
 *
 * <p><b>A REFERENCIA REGISTRADA NUNCA E' O TEXTO.</b>
 * {@link RelatorioConformidade#registrar} guarda ate' vinte referencias por
 * gaveta, e essas referencias sao impressas no relatorio e no CSV. Passar o
 * texto colado como referencia despejaria o dado sensivel dentro do proprio
 * relatorio de vazamento -- e o CSV e' justamente o arquivo que circula por
 * e-mail. Por isso a referencia e' um identificador sintetico
 * ({@code analise-manual-N}), montado aqui, que nao carrega nada do conteudo.
 *
 * <p>Seguro para uso concorrente: {@link Varredura} e {@link PoliticaDlp} sao
 * sem estado, {@link RelatorioConformidade} e' sincronizado, e o unico estado
 * mutavel proprio -- o contador de analises -- e' um {@code long} sob a trava
 * desta instancia.
 */
public final class AnaliseAoVivo {

  /**
   * Prefixo da referencia sintetica. Ver o javadoc da classe: existe para o
   * relatorio ter uma amostra citavel sem citar o conteudo analisado.
   */
  public static final String PREFIXO_REFERENCIA = "analise-manual-";

  private final Varredura varredura;

  private final PoliticaDlp politica;

  private final RelatorioConformidade relatorio;

  private final int tetoDeEntrada;

  private long analises;

  /**
   * @param varredura     o motor; obrigatorio
   * @param politica      a politica; obrigatoria
   * @param relatorio     onde cada analise e' contabilizada; obrigatorio
   * @param tetoDeEntrada teto de caracteres da caixa de texto
   * @throws IllegalArgumentException quando falta um colaborador ou o teto nao
   *         e' positivo. Falha na construcao, isto e', na partida do portlet:
   *         configuracao errada tem de impedir a tela de subir, e nao produzir
   *         uma tela que responde errado.
   */
  public AnaliseAoVivo(Varredura varredura,
                       PoliticaDlp politica,
                       RelatorioConformidade relatorio,
                       int tetoDeEntrada) {
    if (varredura == null || politica == null || relatorio == null) {
      throw new IllegalArgumentException(
          "analise ao vivo exige motor, politica e relatorio");
    }
    if (tetoDeEntrada <= 0) {
      throw new IllegalArgumentException(
          "teto de entrada tem de ser positivo, veio " + tetoDeEntrada);
    }
    this.varredura = varredura;
    this.politica = politica;
    this.relatorio = relatorio;
    this.tetoDeEntrada = tetoDeEntrada;
  }

  public int getTetoDeEntrada() {
    return tetoDeEntrada;
  }

  /**
   * Analisa o texto e devolve o resumo seguro para tela.
   *
   * <p>Caixa em branco NAO e' analisada e NAO entra no relatorio: contar clique
   * em botao como item de acervo inflaria a coluna "Limpo" -- exatamente o
   * numero que o relatorio existe para manter honesto.
   *
   * @param bruto o conteudo da caixa; nulo ou vazio devolve um resumo com
   *              {@link ResumoAnalise#isVazio()} verdadeiro
   * @return sempre um resumo, nunca nulo. Os tres desfechos sao distinguiveis:
   *         {@code isVazio()} (nao havia o que analisar), {@code isFalhou()}
   *         (tentou e quebrou) e o resto (analisou). Nenhum deles se disfarca
   *         de "documento limpo".
   */
  public ResumoAnalise analisar(String bruto) {
    TextoSubmetido submetido;
    try {
      submetido = TextoSubmetido.de(bruto, tetoDeEntrada);
    } catch (RuntimeException e) {
      return falha("Nao foi possivel preparar o texto para analise: " + e.getMessage());
    }

    if (submetido.isVazio()) {
      return new ResumoAnalise(false, null, true, true, null, false, 0, 0, 0L,
                               null, null, null, null, null, 0);
    }

    String referencia = proximaReferencia();

    ResultadoVarredura resultado;
    try {
      resultado = submetido.isTruncado()
          ? varredura.varrerParcial(submetido.getTexto(), submetido.getMotivo())
          : varredura.varrer(submetido.getTexto());
    } catch (RuntimeException e) {
      // O item entra no relatorio mesmo assim, na gaveta de defeito NOSSO.
      // Engolir a falha faria o painel analisar menos do que diz ter analisado
      // -- cobertura sub-relatada, que e' pior que relatorio nenhum porque
      // PARECE cobertura.
      relatorio.registrar(referencia, null, MotivoNaoVarrido.FALHA_NA_VARREDURA);
      return falha("O motor falhou ao analisar este texto: " + e.getMessage());
    }

    try {
      PoliticaDlp.Decisao decisao = politica.decidir(resultado);
      relatorio.registrar(referencia, resultado);
      return projetar(resultado, decisao, submetido);
    } catch (RuntimeException e) {
      relatorio.registrar(referencia, null, MotivoNaoVarrido.FALHA_NA_VARREDURA);
      return falha("A politica falhou ao decidir sobre este texto: " + e.getMessage());
    }
  }

  /** Contador proprio: identifica a analise no relatorio sem citar o conteudo. */
  private synchronized String proximaReferencia() {
    return PREFIXO_REFERENCIA + (++analises);
  }

  /**
   * Copia do laudo apenas o que pode ser mostrado. E' aqui que o texto colado e
   * as ocorrencias cruas deixam de ser alcancaveis -- ver o javadoc de
   * {@link ResumoAnalise}.
   */
  private static ResumoAnalise projetar(ResultadoVarredura resultado,
                                        PoliticaDlp.Decisao decisao,
                                        TextoSubmetido submetido) {
    List<ResumoAnalise.AchadoSeguro> seguros = new ArrayList<>();
    for (Achado achado : resultado.getAchados()) {
      seguros.add(new ResumoAnalise.AchadoSeguro(achado.getRotulo(),
                                                 achado.getSeveridade().name(),
                                                 achado.getQuantidade(),
                                                 achado.getAmostrasMascaradas()));
    }
    return new ResumoAnalise(false,
                             null,
                             false,
                             resultado.isCompleta(),
                             resultado.getMotivoIncompleta(),
                             submetido.isTruncado(),
                             submetido.getTamanhoOriginal(),
                             resultado.getCaracteresVarridos(),
                             resultado.getMilissegundos(),
                             resultado.getClassificacao().name(),
                             decisao.getAcao().name(),
                             decisao.getMotivo(),
                             new ArrayList<>(decisao.getRotulosGatilho()),
                             seguros,
                             resultado.getTotalOcorrencias());
  }

  private static ResumoAnalise falha(String mensagem) {
    return new ResumoAnalise(true, mensagem, false, false, null, false, 0, 0, 0L,
                             null, null, null, null, null, 0);
  }
}
