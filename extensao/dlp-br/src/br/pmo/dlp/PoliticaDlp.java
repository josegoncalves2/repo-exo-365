package br.pmo.dlp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import br.pmo.dlp.RegrasSensiveis.Severidade;

/**
 * A politica: transforma um laudo ({@link ResultadoVarredura}) numa DECISAO.
 *
 * <p>Separada do motor de proposito. O motor responde "o que tem aqui dentro" e
 * a resposta e' a mesma em qualquer instalacao -- CPF e' CPF. A politica
 * responde "e dai?", e a resposta muda por orgao, por espaco e por semana. Se
 * as duas estivessem na mesma classe, mudar o corte de severidade exigiria
 * mexer no codigo que valida digito verificador, e ninguem mexe nisso de bom
 * grado numa sexta-feira.
 *
 * <h2>As tres travas</h2>
 *
 * <b>1. Varredura incompleta nunca e' liberacao.</b> Documento que estourou o
 * teto sai do motor com zero achado, igualzinho a um documento limpo. Se a
 * politica olhasse so' os achados, o caminho trivial de exfiltracao seria
 * empurrar o vazamento para depois do teto. Entao resultado incompleto tem
 * acao propria -- {@link #getAcaoQuandoIncompleta()} -- que nasce em
 * {@link Acao#ALERTAR}: nao trava o trabalho de ninguem, mas produz um item na
 * fila do administrador.
 *
 * <b>2. Corte por severidade E por volume.</b> Um CPF num oficio e' o CPF do
 * requerente; quatrocentos CPFs sao um cadastro saindo pela porta. O corte de
 * volume ({@link #getMinimoOcorrencias()}) existe para a politica poder ser
 * severa com o segundo caso sem inviabilizar o primeiro.
 *
 * <b>3. Isencao e' por ROTULO, nunca por usuario.</b> Nao existe "fulano esta'
 * liberado" nesta classe. Excecao por pessoa e' o mecanismo que, em todo
 * sistema de controle, e' concedido uma vez e nunca revisto -- e vira a porta
 * dos fundos permanente. Se o RH precisa mesmo trafegar CPF, quem cede e' o
 * rotulo naquele espaco, com registro, e nao o crachá de alguem.
 *
 * <p>Imutavel: construida uma vez a partir da configuracao e compartilhada
 * entre threads sem sincronizacao.
 */
public final class PoliticaDlp {

  /**
   * O que fazer. A ordem do enum E' a escala de rigor: {@code compareTo} maior
   * significa resposta mais dura, e e' assim que a decisao final escolhe entre
   * dois gatilhos simultaneos.
   */
  public enum Acao {

    /** Nao faz nada alem de existir no laudo. Para o que e' ruido conhecido. */
    IGNORAR,

    /** Grava o achado no historico. Sem aviso a ninguem. Base do relatorio. */
    REGISTRAR,

    /** Registra e notifica autor e administrador. O documento continua no ar. */
    ALERTAR,

    /** Entrega o documento com os dados sensiveis mascarados. O original fica
     *  acessivel so' a quem tem permissao explicita. */
    MASCARAR,

    /** Recusa a operacao. O arquivo nao e' gravado; o autor ve o motivo. */
    BLOQUEAR,

    /** Aceita e imediatamente retira de circulacao, so' o administrador ve.
     *  Para conteudo que JA' esta' no acervo e nao pode mais ser recusado. */
    QUARENTENAR;

    public static Acao de(String texto, Acao padrao) {
      if (texto == null || texto.trim().isEmpty()) {
        return padrao;
      }
      try {
        return valueOf(texto.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // Configuracao errada NAO pode virar BLOQUEAR (trava o portal) nem
        // IGNORAR (desliga o DLP em silencio): cai no padrao declarado.
        return padrao;
      }
    }
  }

  /** A decisao, com o porque em portugues -- que vai para a tela e o historico. */
  public static final class Decisao {

    private final Acao acao;
    private final String motivo;
    private final Set<String> rotulosGatilho;
    private final Classificacao classificacao;

    Decisao(Acao acao, String motivo, Set<String> rotulosGatilho, Classificacao classificacao) {
      this.acao = acao;
      this.motivo = motivo;
      this.rotulosGatilho = Collections.unmodifiableSet(new LinkedHashSet<>(rotulosGatilho));
      this.classificacao = classificacao;
    }

    public Acao getAcao() {
      return acao;
    }

    /** Frase pronta para o usuario. Nunca contem valor detectado. */
    public String getMotivo() {
      return motivo;
    }

    /** Os rotulos que dispararam. Vazio quando a acao e' IGNORAR/REGISTRAR. */
    public Set<String> getRotulosGatilho() {
      return rotulosGatilho;
    }

    public Classificacao getClassificacao() {
      return classificacao;
    }

    public boolean impedeOperacao() {
      return acao == Acao.BLOQUEAR;
    }

    @Override
    public String toString() {
      return "Decisao[" + acao + ", " + classificacao + ", " + motivo + "]";
    }
  }

  private final Severidade severidadeMinima;

  private final int minimoOcorrencias;

  private final Acao acao;

  private final Acao acaoQuandoIncompleta;

  private final Set<String> rotulosIsentos;

  /**
   * A politica conservadora de partida: registra tudo, alerta no que e' grave,
   * nao bloqueia nada.
   *
   * <p>POR QUE NAO NASCE BLOQUEANDO. Ligar bloqueio no primeiro dia, num acervo
   * que nunca foi varrido, recusa uploads legitimos em massa antes de alguem
   * ter conferido uma amostra dos achados. A administracao entao desliga o DLP
   * inteiro -- e ai' nao ha' protecao nenhuma, que e' pior do que a que se
   * tinha. A ordem certa e': ALERTAR, conferir o relatorio por algumas semanas,
   * ajustar isencoes, e SO' ENTAO endurecer.
   */
  public static PoliticaDlp padrao() {
    return new PoliticaDlp(Severidade.ALTA, 1, Acao.ALERTAR, Acao.ALERTAR, null);
  }

  public PoliticaDlp(Severidade severidadeMinima,
                     int minimoOcorrencias,
                     Acao acao,
                     Acao acaoQuandoIncompleta,
                     Set<String> rotulosIsentos) {
    if (severidadeMinima == null) {
      throw new IllegalArgumentException("severidade minima e' obrigatoria");
    }
    if (acao == null || acaoQuandoIncompleta == null) {
      throw new IllegalArgumentException("acao e' obrigatoria");
    }
    if (minimoOcorrencias < 1) {
      throw new IllegalArgumentException("minimo de ocorrencias tem de ser >= 1");
    }
    this.severidadeMinima = severidadeMinima;
    this.minimoOcorrencias = minimoOcorrencias;
    this.acao = acao;
    this.acaoQuandoIncompleta = acaoQuandoIncompleta;
    // TreeSet: comparacao de rotulo e' exata, e a ordem estavel torna a decisao
    // reproduzivel entre execucoes -- requisito de auditoria.
    Set<String> isentos = new TreeSet<>();
    if (rotulosIsentos != null) {
      for (String rotulo : rotulosIsentos) {
        if (rotulo != null && !rotulo.trim().isEmpty()) {
          isentos.add(rotulo.trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    this.rotulosIsentos = Collections.unmodifiableSet(isentos);
  }

  public Severidade getSeveridadeMinima() {
    return severidadeMinima;
  }

  public int getMinimoOcorrencias() {
    return minimoOcorrencias;
  }

  public Acao getAcao() {
    return acao;
  }

  public Acao getAcaoQuandoIncompleta() {
    return acaoQuandoIncompleta;
  }

  public Set<String> getRotulosIsentos() {
    return rotulosIsentos;
  }

  /**
   * A decisao para um laudo.
   *
   * @param resultado o laudo do motor; nulo e' tratado como varredura que nao
   *                  aconteceu -- e portanto NAO como documento limpo
   */
  public Decisao decidir(ResultadoVarredura resultado) {
    if (resultado == null) {
      return new Decisao(acaoQuandoIncompleta,
                         "O documento nao chegou a ser analisado.",
                         Collections.<String>emptySet(),
                         Classificacao.PUBLICO);
    }

    Set<String> gatilhos = new LinkedHashSet<>();
    for (Achado achado : resultado.getAchados()) {
      if (rotulosIsentos.contains(achado.getRotulo().toUpperCase(Locale.ROOT))) {
        continue;
      }
      if (achado.getSeveridade().compareTo(severidadeMinima) < 0) {
        continue;
      }
      if (achado.getQuantidade() < minimoOcorrencias) {
        continue;
      }
      gatilhos.add(achado.getRotulo());
    }

    Acao porAchado = gatilhos.isEmpty() ? Acao.REGISTRAR : acao;
    Acao porIncompletude = resultado.isCompleta() ? Acao.IGNORAR : acaoQuandoIncompleta;

    // Entre os dois motivos, vale o mais rigoroso. Nunca a media, nunca o
    // ultimo avaliado: rigor so' sobe.
    Acao escolhida = porAchado.compareTo(porIncompletude) >= 0 ? porAchado : porIncompletude;

    return new Decisao(escolhida,
                       explicar(escolhida, gatilhos, resultado),
                       gatilhos,
                       resultado.getClassificacao());
  }

  private String explicar(Acao escolhida, Set<String> gatilhos, ResultadoVarredura resultado) {
    if (!resultado.isCompleta() && gatilhos.isEmpty()) {
      return "O documento nao pode ser analisado por inteiro ("
             + resultado.getMotivoIncompleta()
             + "). Ele NAO foi considerado livre de dados sensiveis.";
    }
    if (gatilhos.isEmpty()) {
      return "Nenhum dado sensivel acima do criterio configurado ("
             + severidadeMinima + ", a partir de " + minimoOcorrencias + " ocorrencia"
             + (minimoOcorrencias > 1 ? "s" : "") + ").";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Foram encontrados dados pessoais ou sensiveis no documento: ");
    int i = 0;
    for (String rotulo : gatilhos) {
      if (i++ > 0) {
        sb.append(", ");
      }
      Achado achado = resultado.getAchado(rotulo);
      sb.append(rotulo.replace('_', ' ').toLowerCase(Locale.ROOT));
      if (achado != null) {
        sb.append(" (").append(achado.getQuantidade()).append(')');
      }
    }
    sb.append(". Classificacao: ").append(resultado.getClassificacao()).append('.');
    if (!resultado.isCompleta()) {
      sb.append(" A analise foi parcial (").append(resultado.getMotivoIncompleta()).append(").");
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "PoliticaDlp[>=" + severidadeMinima + " x" + minimoOcorrencias
           + " -> " + acao + ", incompleta -> " + acaoQuandoIncompleta
           + ", isentos=" + rotulosIsentos + ']';
  }
}
