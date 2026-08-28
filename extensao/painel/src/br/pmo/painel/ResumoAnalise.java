package br.pmo.painel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * O resultado de uma analise da caixa "Analisar texto", <b>projetado para conter
 * apenas o que pode ser mostrado</b>.
 *
 * <p><b>POR QUE ESTA CLASSE EXISTE, EM VEZ DE GUARDAR O
 * {@code ResultadoVarredura} DIRETO.</b> O laudo do motor carrega, dentro de
 * cada {@code Achado}, a lista de {@code Ocorrencia} -- e {@code Ocorrencia}
 * guarda o trecho BRUTO, o CPF em claro. Esse laudo precisa existir durante a
 * decisao da politica, mas nao pode sobreviver a ela, por dois motivos
 * concretos:
 *
 * <ol>
 *   <li><b>A sessao.</b> A tela mostra o resultado da ultima analise depois de
 *       um POST, entao o resultado atravessa uma requisicao guardado na sessao
 *       do portlet. Guardar o laudo cru ali significa deixar o dado sensivel
 *       vivo na memoria do servidor pelo tempo inteiro da sessao do
 *       administrador -- e, em portal configurado para persistir ou replicar
 *       sessao, significa grava-lo em disco ou manda-lo pela rede. O
 *       administrador colou o texto para SABER se ha' dado sensivel nele; a
 *       resposta nao pode ser o portal ficar com uma copia;</li>
 *   <li><b>A tela.</b> Enquanto o objeto que chega ao renderizador tiver um
 *       getter capaz de devolver o valor em claro, uma linha nova de HTML pode
 *       imprimi-lo por descuido. Aqui esse getter simplesmente nao existe: as
 *       amostras ja' chegam mascaradas por
 *       {@code Achado.getAmostrasMascaradas()}, e nao ha' caminho de volta.</li>
 * </ol>
 *
 * <p>Ou seja, a garantia nao e' "lembrar de nao imprimir": e' nao ter o que
 * imprimir. Depois que {@link AnaliseAoVivo} monta este resumo, o laudo cru fica
 * sem referencia e o coletor de lixo leva junto o texto colado.
 *
 * <p>{@link Serializable} de proposito: sessao de portlet pode ser serializada
 * pelo contentor. Todos os campos sao tipos simples ou colecoes de
 * {@link String}.
 *
 * <p>Imutavel.
 */
public final class ResumoAnalise implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Um achado, ja' sem nenhuma ocorrencia crua. */
  public static final class AchadoSeguro implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String rotulo;

    private final String severidade;

    private final int quantidade;

    private final List<String> amostrasMascaradas;

    public AchadoSeguro(String rotulo, String severidade, int quantidade,
                        List<String> amostrasMascaradas) {
      this.rotulo = rotulo;
      this.severidade = severidade;
      this.quantidade = quantidade;
      this.amostrasMascaradas = amostrasMascaradas == null
          ? Collections.<String>emptyList()
          : Collections.unmodifiableList(new ArrayList<>(amostrasMascaradas));
    }

    public String getRotulo() {
      return rotulo;
    }

    public String getSeveridade() {
      return severidade;
    }

    public int getQuantidade() {
      return quantidade;
    }

    /** Exemplos JA' mascarados pelo motor. Nao existe versao em claro aqui. */
    public List<String> getAmostrasMascaradas() {
      return amostrasMascaradas;
    }
  }

  private final boolean falhou;

  private final String erro;

  private final boolean vazio;

  private final boolean completa;

  private final String motivoIncompleta;

  private final boolean truncadoPeloPainel;

  private final int tamanhoOriginal;

  private final int caracteresVarridos;

  private final long milissegundos;

  private final String classificacao;

  private final String acao;

  private final String motivoDecisao;

  private final List<String> rotulosGatilho;

  private final List<AchadoSeguro> achados;

  private final int totalOcorrencias;

  ResumoAnalise(boolean falhou,
                String erro,
                boolean vazio,
                boolean completa,
                String motivoIncompleta,
                boolean truncadoPeloPainel,
                int tamanhoOriginal,
                int caracteresVarridos,
                long milissegundos,
                String classificacao,
                String acao,
                String motivoDecisao,
                List<String> rotulosGatilho,
                List<AchadoSeguro> achados,
                int totalOcorrencias) {
    this.falhou = falhou;
    this.erro = erro;
    this.vazio = vazio;
    this.completa = completa;
    this.motivoIncompleta = motivoIncompleta;
    this.truncadoPeloPainel = truncadoPeloPainel;
    this.tamanhoOriginal = tamanhoOriginal;
    this.caracteresVarridos = caracteresVarridos;
    this.milissegundos = milissegundos;
    this.classificacao = classificacao;
    this.acao = acao;
    this.motivoDecisao = motivoDecisao;
    this.rotulosGatilho = rotulosGatilho == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(rotulosGatilho));
    this.achados = achados == null
        ? Collections.<AchadoSeguro>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(achados));
    this.totalOcorrencias = totalOcorrencias;
  }

  /**
   * Verdadeiro quando a analise NAO aconteceu por erro do painel ou do motor.
   *
   * <p>E' o estado que se distingue de todos os outros: um resumo com
   * {@code falhou=true} nunca tem achado e nunca tem classificacao valida, e a
   * tela precisa dizer "nao consegui analisar" em vez de "nada encontrado" --
   * que sao respostas opostas.
   */
  public boolean isFalhou() {
    return falhou;
  }

  /** A mensagem do erro, em portugues. Nulo quando {@link #isFalhou()} e' falso. */
  public String getErro() {
    return erro;
  }

  /** Verdadeiro quando o administrador clicou em Analisar com a caixa em branco. */
  public boolean isVazio() {
    return vazio;
  }

  /** Falso quando o motor nao olhou o texto inteiro. Ver {@link #getMotivoIncompleta()}. */
  public boolean isCompleta() {
    return completa;
  }

  public String getMotivoIncompleta() {
    return motivoIncompleta;
  }

  /** Verdadeiro quando foi o teto DO PAINEL que cortou, e nao o do motor. */
  public boolean isTruncadoPeloPainel() {
    return truncadoPeloPainel;
  }

  public int getTamanhoOriginal() {
    return tamanhoOriginal;
  }

  public int getCaracteresVarridos() {
    return caracteresVarridos;
  }

  public long getMilissegundos() {
    return milissegundos;
  }

  /** Nome da classificacao derivada ({@code PUBLICO}...{@code SIGILOSO}). */
  public String getClassificacao() {
    return classificacao;
  }

  /** Nome da acao decidida pela politica ({@code REGISTRAR}, {@code ALERTAR}...). */
  public String getAcao() {
    return acao;
  }

  /** A frase de justificativa da politica. O motor garante que nao contem valor. */
  public String getMotivoDecisao() {
    return motivoDecisao;
  }

  public List<String> getRotulosGatilho() {
    return rotulosGatilho;
  }

  public List<AchadoSeguro> getAchados() {
    return achados;
  }

  public int getTotalOcorrencias() {
    return totalOcorrencias;
  }

  /**
   * Verdadeiro so' quando o texto foi lido POR INTEIRO e nada foi encontrado.
   *
   * <p><b>{@code isCompleta()} FAZ PARTE DESTA CONTA, E E' O PONTO INTEIRO.</b>
   * Um texto cortado no teto sai daqui com zero achado -- identico a um texto
   * realmente inofensivo. Se "limpo" fosse apenas "lista de achados vazia", o
   * jeito trivial de a tela dizer "nada encontrado" sobre um CPF seria colar
   * texto de enchimento antes dele ate' passar do teto. Nao saber nao e' saber
   * que nao ha'.
   */
  public boolean isLimpo() {
    return !falhou && !vazio && completa && achados.isEmpty();
  }
}
