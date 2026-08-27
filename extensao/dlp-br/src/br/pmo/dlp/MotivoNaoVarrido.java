package br.pmo.dlp;

import java.util.Locale;

/**
 * Por que um item ficou em {@link CategoriaConformidade#NAO_VARRIDO}, agrupado
 * em classes que levam a DECISOES DIFERENTES.
 *
 * <p><b>POR QUE AGRUPAR, E NAO SO' LISTAR O MOTIVO.</b> O motivo que o
 * adaptador escreve carrega numeros: {@code "arquivo de 41943040 bytes acima do
 * teto de 16777216"}. Agrupar por texto cru da' um grupo por arquivo, e um
 * relatorio com dez mil grupos de um item cada nao e' relatorio. Agrupar tudo
 * numa linha so' -- "10.412 nao varridos" -- apaga a unica informacao que
 * decide, porque:
 *
 * <ul>
 *   <li>{@link #ACIMA_DO_TETO_DE_BYTES} se resolve trocando um numero em
 *       {@code exo.properties}. Custa zero;</li>
 *   <li>{@link #PROVAVEL_DIGITALIZACAO} so' se resolve com OCR, que e' container
 *       novo, memoria e decisao de infraestrutura. Custa dinheiro;</li>
 *   <li>{@link #RECUSADO_POR_SEGURANCA} nao e' para resolver: e' para
 *       investigar, porque alguem enviou um arquivo construido para explodir.</li>
 * </ul>
 *
 * <p>Somar os tres numa coluna esconde justamente o que separa "mudar uma
 * linha" de "abrir processo de compra" e de "chamar a seguranca".
 *
 * <h2>Sobre classificar por texto</h2>
 * {@link #classificar(String)} le a prosa que o chamador escreveu. Isso e'
 * fragil por natureza -- muda-se a redacao no adaptador e a classificacao muda
 * de gaveta em silencio. Duas defesas, e a segunda e' a que importa:
 *
 * <ol>
 *   <li>ha' sobrecarga que aceita o codigo direto
 *       ({@link RelatorioConformidade#registrar(String, ResultadoVarredura,
 *       MotivoNaoVarrido)}), que e' o caminho duravel;</li>
 *   <li>o que nao casa cai em {@link #OUTRO}, e o relatorio IMPRIME amostras do
 *       texto cru de OUTRO. A deriva fica <b>barulhenta</b> em vez de
 *       silenciosa: quem ler o relatorio ve "OUTRO: 8.311" e vai atras.</li>
 * </ol>
 */
public enum MotivoNaoVarrido {

  /**
   * Arquivo construido para explodir (bomba de descompressao) ou que estourou
   * limite de seguranca. Nao e' problema de capacidade: e' incidente.
   */
  RECUSADO_POR_SEGURANCA("Recusado por limite de seguranca", "investigar: possivel ataque"),

  /**
   * Nenhum extrator leu o binario, ou o formato abriu e nao tinha texto.
   * Quase sempre e' digitalizacao de papel. E' o numero que justifica OCR.
   */
  PROVAVEL_DIGITALIZACAO("Provavel digitalizacao (sem camada de texto)", "exige OCR"),

  /** Formato desconhecido, corrompido ou cifrado. */
  FORMATO_NAO_SUPORTADO("Formato nao suportado, corrompido ou cifrado", "avaliar caso a caso"),

  /** Maior que o teto de bytes do chamador: o binario nem chegou a ser aberto. */
  ACIMA_DO_TETO_DE_BYTES("Acima do teto de bytes", "revisar configuracao"),

  /** Aberto, mas o texto passou do teto de caracteres do motor. */
  ACIMA_DO_TETO_DE_CARACTERES("Acima do teto de caracteres", "revisar configuracao"),

  /** O motor gastou o orcamento de tempo antes de aplicar todas as regras. */
  ORCAMENTO_DE_TEMPO_ESGOTADO("Orcamento de tempo esgotado", "revisar configuracao"),

  /** Item sem binario associado. Costuma ser pasta, atalho ou registro vazio. */
  SEM_CONTEUDO_BINARIO("Sem conteudo binario", "normalmente inofensivo"),

  /**
   * O DLP ERROU ao varrer este item: excecao de leitura, de repositorio, de
   * rede. Nao e' limitacao de formato -- e' defeito nosso ou da infraestrutura.
   *
   * <p>Gaveta propria porque o encaminhamento e' oposto ao dos vizinhos:
   * {@link #PROVAVEL_DIGITALIZACAO} manda comprar OCR e
   * {@link #ACIMA_DO_TETO_DE_BYTES} manda mexer na configuracao; esta manda ler
   * o log e corrigir codigo. Somada a qualquer uma das outras, viraria pedido de
   * orcamento para consertar um bug.
   *
   * <p>Existe tambem para tapar um sumidouro: item que estoura excecao no meio
   * da varredura nao aparecia em gaveta nenhuma, e o relatorio dizia ter
   * analisado menos itens do que de fato encontrou -- cobertura sub-relatada,
   * que e' pior do que relatorio nenhum porque PARECE cobertura.
   */
  FALHA_NA_VARREDURA("Falha na varredura (erro do DLP)", "ler o log e corrigir"),

  /** Nao reconhecido. Ver as amostras impressas no relatorio. */
  OUTRO("Outro", "ver amostras no relatorio");

  private final String rotulo;

  private final String encaminhamento;

  MotivoNaoVarrido(String rotulo, String encaminhamento) {
    this.rotulo = rotulo;
    this.encaminhamento = encaminhamento;
  }

  public String getRotulo() {
    return rotulo;
  }

  /** O que fazer com esta linha do relatorio. Vai impresso ao lado do numero. */
  public String getEncaminhamento() {
    return encaminhamento;
  }

  /**
   * Le a prosa do motivo e devolve a gaveta.
   *
   * <p><b>A ORDEM DOS TESTES E' PRIORIDADE, NAO ACASO.</b> Motivos se ACUMULAM
   * ({@code "extracao parcial do PDF; documento maior que o teto..."}), entao um
   * texto pode conter marcas de duas gavetas. Vence a mais grave e mais
   * acionavel -- na ordem de declaracao do enum, de cima para baixo. Um item que
   * foi recusado por seguranca E estourou o teto e' um incidente de seguranca,
   * nao um problema de configuracao.
   *
   * @param motivo texto livre; nulo ou vazio devolve {@link #OUTRO}
   */
  public static MotivoNaoVarrido classificar(String motivo) {
    if (motivo == null || motivo.trim().isEmpty()) {
      return OUTRO;
    }
    String m = motivo.toLowerCase(Locale.ROOT);

    if (contem(m, "bomba", "limite de seguranca", "compressao")) {
      return RECUSADO_POR_SEGURANCA;
    }
    // ANTES da regra de OCR de proposito: a mensagem de estouro de tempo do
    // proprio motor de OCR contem a palavra "OCR", e cairia em
    // PROVAVEL_DIGITALIZACAO -- mandando comprar OCR quando o OCR ja' existe e
    // o que falta e' capacidade. Sao encaminhamentos opostos.
    if (contem(m, "passou do teto de", "foi encerrado")) {
      return ORCAMENTO_DE_TEMPO_ESGOTADO;
    }
    if (contem(m, "ocr", "digitaliza", "nenhum extrator", "sem nenhum texto", "sem texto")) {
      return PROVAVEL_DIGITALIZACAO;
    }
    if (contem(m, "nao suportado", "corrompido", "cifrado", "falha ao ler")) {
      return FORMATO_NAO_SUPORTADO;
    }
    if (contem(m, "teto de bytes", "bytes acima do teto")) {
      return ACIMA_DO_TETO_DE_BYTES;
    }
    // AND, nao OR: "caracteres" sozinho aparece em prosa demais, e "teto de"
    // sozinho tambem casa com teto de bytes. So' os dois juntos identificam.
    if (contemTodos(m, "teto de", "caracteres")) {
      return ACIMA_DO_TETO_DE_CARACTERES;
    }
    if (contem(m, "orcamento", "esgotado")) {
      return ORCAMENTO_DE_TEMPO_ESGOTADO;
    }
    if (contem(m, "sem conteudo binario", "sem propriedade de dados", "sem dados binarios")) {
      return SEM_CONTEUDO_BINARIO;
    }
    if (contem(m, "excecao", "exception", "falhou ao varrer", "erro ao varrer")) {
      return FALHA_NA_VARREDURA;
    }
    return OUTRO;
  }

  /** Verdadeiro se QUALQUER marca aparece. */
  private static boolean contem(String alvo, String... marcas) {
    for (String marca : marcas) {
      if (alvo.contains(marca)) {
        return true;
      }
    }
    return false;
  }

  /** Verdadeiro so' se TODAS as marcas aparecem. */
  private static boolean contemTodos(String alvo, String... marcas) {
    for (String marca : marcas) {
      if (!alvo.contains(marca)) {
        return false;
      }
    }
    return true;
  }
}
