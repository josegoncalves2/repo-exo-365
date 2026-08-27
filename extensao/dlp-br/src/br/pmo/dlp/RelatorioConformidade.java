package br.pmo.dlp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acumulador do relatorio de conformidade: recebe um laudo por vez e mantem as
 * contas do acervo inteiro.
 *
 * <h2>Seguro para uso concorrente, e isso NAO e' zelo excessivo</h2>
 * {@code DlpOperationProcessorImpl} tem
 * {@code Executors.newCachedThreadPool()} e processa o lote em paralelo (lido do
 * bytecode). Um contador {@code int++} sob duas threads perde incrementos em
 * silencio -- e relatorio de conformidade que perde contagem e' relatorio que
 * mente, com a agravante de mentir PARA MENOS: some item da coluna que
 * interessa. Todos os metodos que escrevem sao {@code synchronized}, e a leitura
 * sai por {@link #instantaneo()}, que devolve copia imutavel. Prende-se a
 * instancia por alguns microssegundos por item; a varredura, que custa
 * milissegundos, domina de longe.
 *
 * <h2>Amostras limitadas, contagens ilimitadas</h2>
 * Contador de acervo com dezenas de milhares de itens cabe em memoria; a LISTA
 * desses itens nao -- e o relatorio nao precisa dela. Guarda-se
 * {@link #MAX_AMOSTRAS} referencias por gaveta, e a contagem verdadeira segue
 * inteira. O administrador precisa do numero para decidir e de alguns exemplos
 * para conferir; a lista completa e' consulta, nao relatorio.
 *
 * <h2>O que NUNCA entra aqui</h2>
 * Valor detectado, nem mascarado. O relatorio guarda ROTULO e CONTAGEM. Um
 * relatorio de vazamento que carrega os dados vazados e' o vazamento seguindo
 * viagem num anexo de e-mail -- que e' como esse arquivo circula.
 */
public final class RelatorioConformidade {

  /** Referencias guardadas por gaveta. Vinte: cabe numa tela, basta para
   *  reconhecer um padrao (vinte digitalizacoes do mesmo setor saltam a vista). */
  public static final int MAX_AMOSTRAS = 20;

  private final String titulo;

  private final long inicio;

  private final Map<CategoriaConformidade, Integer> porCategoria =
      new EnumMap<>(CategoriaConformidade.class);

  private final Map<MotivoNaoVarrido, Integer> porMotivo =
      new EnumMap<>(MotivoNaoVarrido.class);

  private final Map<Classificacao, Integer> porClassificacao =
      new EnumMap<>(Classificacao.class);

  /** Quantos ITENS contem o rotulo. Diferente de quantas OCORRENCIAS ha'. */
  private final Map<String, Integer> itensPorRotulo = new LinkedHashMap<>();

  /** Soma de ocorrencias do rotulo no acervo. */
  private final Map<String, Long> ocorrenciasPorRotulo = new LinkedHashMap<>();

  private final Map<CategoriaConformidade, List<String>> amostrasPorCategoria =
      new EnumMap<>(CategoriaConformidade.class);

  private final Map<MotivoNaoVarrido, List<String>> amostrasPorMotivo =
      new EnumMap<>(MotivoNaoVarrido.class);

  /** Texto cru dos motivos que cairam em OUTRO: e' o alarme de deriva. */
  private final List<String> amostrasDeMotivoCru = new ArrayList<>();

  private int total;

  private int naoVarridosComAchado;

  public RelatorioConformidade(String titulo) {
    this.titulo = titulo == null || titulo.trim().isEmpty()
                  ? "Relatorio de conformidade — DLP" : titulo.trim();
    this.inicio = System.currentTimeMillis();
  }

  /**
   * Registra um item, deduzindo o motivo da prosa do laudo.
   *
   * @param referenciaItem identificador do item (id do no', caminho); pode ser
   *                       nulo, e ai' o item conta mas nao vira amostra
   * @param resultado      o laudo; nulo conta como NAO_VARRIDO
   */
  public void registrar(String referenciaItem, ResultadoVarredura resultado) {
    MotivoNaoVarrido motivo = null;
    if (resultado == null) {
      motivo = MotivoNaoVarrido.OUTRO;
    } else if (!resultado.isCompleta()) {
      motivo = MotivoNaoVarrido.classificar(resultado.getMotivoIncompleta());
    }
    registrar(referenciaItem, resultado, motivo);
  }

  /**
   * Registra um item com o motivo JA' CLASSIFICADO pelo chamador.
   *
   * <p>E' o caminho duravel: dispensa a leitura de prosa, que quebra em
   * silencio quando alguem reescreve uma mensagem de log. Use este sempre que a
   * causa for conhecida na origem.
   *
   * @param motivo so' e' usado quando o laudo esta' incompleto; nulo pede a
   *               deducao pela prosa
   */
  public synchronized void registrar(String referenciaItem,
                                     ResultadoVarredura resultado,
                                     MotivoNaoVarrido motivo) {
    total++;
    CategoriaConformidade categoria = CategoriaConformidade.de(resultado);
    somar(porCategoria, categoria);
    guardarAmostra(amostrasPorCategoria, categoria, referenciaItem);

    if (categoria == CategoriaConformidade.NAO_VARRIDO) {
      MotivoNaoVarrido gaveta = motivo != null
          ? motivo
          : MotivoNaoVarrido.classificar(resultado == null ? null : resultado.getMotivoIncompleta());
      somar(porMotivo, gaveta);
      guardarAmostra(amostrasPorMotivo, gaveta, referenciaItem);
      if (gaveta == MotivoNaoVarrido.OUTRO && resultado != null
          && resultado.getMotivoIncompleta() != null
          && amostrasDeMotivoCru.size() < MAX_AMOSTRAS) {
        amostrasDeMotivoCru.add(resultado.getMotivoIncompleta());
      }
    }

    if (resultado == null) {
      return;
    }

    // CLASSIFICACAO SO' PARA O QUE FOI LIDO POR INTEIRO.
    // Um item nao varrido recebe Classificacao.PUBLICO do motor pelo mesmo
    // motivo que recebe "limpo": nao se achou nada no pedaco lido. Somar isso
    // ao quadro de classificacao publica a frase "3 documentos sao PUBLICOS"
    // quando dois deles nunca foram abertos -- exatamente a mentira que este
    // relatorio existe para impedir, e ainda por cima com carimbo de
    // classificacao formal. Nao varrido nao tem classificacao: tem pendencia.
    if (categoria != CategoriaConformidade.NAO_VARRIDO) {
      somar(porClassificacao, resultado.getClassificacao());
    }

    if (!resultado.isLimpo()) {
      if (categoria == CategoriaConformidade.NAO_VARRIDO) {
        // O pior balde do acervo: nao deu para ler tudo, e o pedaco lido JA'
        // tinha dado sensivel. Merece linha propria, senao some dentro de
        // "nao varrido" e parece so' um problema de capacidade.
        naoVarridosComAchado++;
      }
      for (Achado achado : resultado.getAchados()) {
        String rotulo = achado.getRotulo();
        itensPorRotulo.merge(rotulo, 1, Integer::sum);
        ocorrenciasPorRotulo.merge(rotulo, (long) achado.getQuantidade(), Long::sum);
      }
    }
  }

  /** Copia imutavel e coerente do estado atual. Seguro para ler de outra thread. */
  public synchronized InstantaneoConformidade instantaneo() {
    return new InstantaneoConformidade(titulo,
                                       inicio,
                                       System.currentTimeMillis(),
                                       total,
                                       naoVarridosComAchado,
                                       porCategoria,
                                       porMotivo,
                                       porClassificacao,
                                       itensPorRotulo,
                                       ocorrenciasPorRotulo,
                                       amostrasPorCategoria,
                                       amostrasPorMotivo,
                                       amostrasDeMotivoCru);
  }

  public synchronized int getTotal() {
    return total;
  }

  private static <C> void somar(Map<C, Integer> mapa, C chave) {
    mapa.merge(chave, 1, Integer::sum);
  }

  private static <C> void guardarAmostra(Map<C, List<String>> mapa, C chave, String referencia) {
    if (referencia == null || referencia.trim().isEmpty()) {
      return;
    }
    List<String> amostras = mapa.computeIfAbsent(chave, c -> new ArrayList<>(MAX_AMOSTRAS));
    if (amostras.size() < MAX_AMOSTRAS) {
      amostras.add(referencia);
    }
  }
}
