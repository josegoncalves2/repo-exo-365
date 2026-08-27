package br.pmo.dlp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.pmo.dlp.RegrasSensiveis.Regra;
import br.pmo.dlp.RegrasSensiveis.Severidade;

/**
 * O motor: recebe texto, devolve {@link ResultadoVarredura}.
 *
 * <p>Duas responsabilidades que nao existem em {@link RegrasSensiveis}, e que
 * sao justamente onde um DLP ingenuo quebra em producao:
 *
 * <h2>1. Desduplicacao por sobreposicao</h2>
 * O catalogo tem regras que casam com o MESMO trecho de proposito. CPF e CNH
 * tem onze digitos; um numero pode fechar nos dois algoritmos. Um CPF cujo
 * terceiro digito e' 9 tambem casa com TELEFONE. Sem desduplicar, o relatorio
 * de conformidade diz "3 dados pessoais" onde ha' UM -- e um numero inflado num
 * relatorio de LGPD e' pior que numero nenhum, porque destroi a confianca em
 * todos os outros numeros do mesmo relatorio.
 *
 * <p>A regra de desempate, quando dois achados ocupam o mesmo trecho:
 * <ol>
 *   <li>vence a MAIOR severidade -- classificar para baixo e' o erro caro;</li>
 *   <li>empatou, vence quem vem ANTES no catalogo, que esta' em ordem de
 *       especificidade (CPF antes de CNH, ambos antes de TELEFONE).</li>
 * </ol>
 * Determinismo importa: o mesmo documento tem de dar o mesmo laudo hoje e no
 * mes que vem, senao nao ha' auditoria possivel.
 *
 * <h2>2. Orcamento</h2>
 * Varredura roda dentro do upload do usuario e dentro de um job que percorre o
 * acervo. Sem teto, um unico arquivo de 300 MB segura uma thread do Tomcat e
 * derruba o portal para todo mundo -- negacao de servico de graca, disparada
 * por quem so' precisa ter permissao de anexar arquivo. Por isso ha' teto de
 * caracteres e teto de tempo, e estourar o teto NAO produz "documento limpo":
 * produz {@code completa=false}, que a politica trata como suspeita.
 *
 * <p>Sem estado. Uma instancia pode ser compartilhada por qualquer numero de
 * threads.
 */
public final class Varredura {

  /**
   * Teto padrao de caracteres varridos: 2.000.000, cerca de 400 paginas de
   * texto corrido. Escolhido por medida de memoria, nao por gosto: no pior caso
   * plausivel (planilha so' de CPFs) isso da' ~150 mil ocorrencias, ~10 MB de
   * objetos vivos durante a varredura. Dez vezes mais e a JVM do portal, que
   * roda com heap apertado, comeca a paginar.
   */
  public static final int TETO_CARACTERES_PADRAO = 2_000_000;

  /**
   * Teto padrao de tempo: 10 segundos. Um upload que trava 10s ja' e' ruim;
   * 60s e' o usuario clicando de novo e dobrando a carga.
   */
  public static final long TETO_MILISSEGUNDOS_PADRAO = 10_000L;

  private final int tetoCaracteres;

  private final long tetoMilissegundos;

  /** Motor com os tetos padrao. */
  public Varredura() {
    this(TETO_CARACTERES_PADRAO, TETO_MILISSEGUNDOS_PADRAO);
  }

  public Varredura(int tetoCaracteres, long tetoMilissegundos) {
    if (tetoCaracteres <= 0) {
      throw new IllegalArgumentException("teto de caracteres tem de ser positivo");
    }
    if (tetoMilissegundos <= 0) {
      throw new IllegalArgumentException("teto de tempo tem de ser positivo");
    }
    this.tetoCaracteres = tetoCaracteres;
    this.tetoMilissegundos = tetoMilissegundos;
  }

  public int getTetoCaracteres() {
    return tetoCaracteres;
  }

  public long getTetoMilissegundos() {
    return tetoMilissegundos;
  }

  /**
   * Varre o texto e devolve o laudo.
   *
   * @param texto conteudo ja' extraido; nulo ou vazio devolve resultado limpo e
   *              COMPLETO -- documento sem texto nao e' documento nao varrido
   * @return o laudo, nunca nulo
   */
  public ResultadoVarredura varrer(String texto) {
    return varrer(texto, null);
  }

  /**
   * Varre um texto que quem chama SABE ser apenas parte do documento.
   *
   * <p><b>POR QUE ISTO PRECISA EXISTIR, E POR QUE O MOTIVO E' OBRIGATORIO.</b>
   * O motor so' enxerga a {@code String} que recebe. Ele consegue perceber que
   * ESTOUROU o proprio teto, mas nao tem como saber que o texto chegou
   * capenga antes de entrar aqui -- e chega capenga o tempo todo:
   *
   * <ul>
   *   <li>PDF digitalizado, sem camada de texto: nenhum extrator le, e o
   *       chamador acaba varrendo so' o NOME do arquivo;</li>
   *   <li>arquivo acima do teto de bytes do chamador, que nem e' aberto;</li>
   *   <li>documento cifrado, corrompido, ou de formato sem extrator.</li>
   * </ul>
   *
   * <p>Nesses casos {@link #varrer(String)} devolveria {@code completa=true} e
   * {@code limpo=true} -- ou seja, <b>a ficha funcional digitalizada seria
   * classificada como PUBLICO</b>, e a politica nao teria como distinguir isso
   * de um documento realmente inofensivo. Este metodo fecha esse buraco: o
   * laudo sai marcado incompleto e {@link PoliticaDlp#getAcaoQuandoIncompleta()}
   * dispara.
   *
   * <p>O motivo e' OBRIGATORIO de proposito. Varredura parcial sem motivo
   * escrito e' um alerta que o administrador nao consegue julgar, e alerta que
   * nao se consegue julgar e' alerta que se aprende a ignorar.
   *
   * @param texto            o pedaco de texto que se conseguiu obter
   * @param motivoIncompleta por que o texto e' parcial, em portugues, para ir
   *                         direto ao relatorio e a' tela; nulo ou em branco
   *                         significa varredura completa e cai em
   *                         {@link #varrer(String)}
   */
  public ResultadoVarredura varrerParcial(String texto, String motivoIncompleta) {
    if (motivoIncompleta == null || motivoIncompleta.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "varredura parcial exige motivo escrito: sem ele o alerta nao e' julgavel");
    }
    return varrer(texto, motivoIncompleta.trim());
  }

  /**
   * O corpo comum. {@code motivoExterno} nao nulo forca {@code completa=false}
   * antes mesmo de a varredura comecar.
   */
  private ResultadoVarredura varrer(String texto, String motivoExterno) {
    long inicio = System.currentTimeMillis();

    if (texto == null || texto.isEmpty()) {
      return new ResultadoVarredura(new ArrayList<>(),
                                    motivoExterno == null,
                                    motivoExterno,
                                    0,
                                    0L);
    }

    String alvo = texto;
    boolean completa = motivoExterno == null;
    String motivo = motivoExterno;

    if (alvo.length() > tetoCaracteres) {
      alvo = alvo.substring(0, tetoCaracteres);
      completa = false;
      // ACUMULA, nao substitui: um documento pode chegar capenga do extrator E
      // ainda assim estourar o teto. Sobrescrever perderia metade do diagnostico.
      motivo = juntar(motivo, "documento maior que o teto de " + tetoCaracteres
                              + " caracteres; varridos os primeiros " + tetoCaracteres);
    }

    // Fase 1: coleta bruta, regra a regra, na ordem do catalogo.
    List<Candidato> candidatos = new ArrayList<>();
    for (Regra regra : RegrasSensiveis.regras()) {
      long gasto = System.currentTimeMillis() - inicio;
      if (gasto > tetoMilissegundos) {
        completa = false;
        motivo = juntar(motivo, "orcamento de " + tetoMilissegundos
                                + " ms esgotado apos " + gasto
                                + " ms; regras restantes nao foram aplicadas");
        break;
      }
      for (Ocorrencia ocorrencia : regra.ocorrenciasEm(alvo)) {
        candidatos.add(new Candidato(regra, ocorrencia));
      }
    }

    // Fase 2: desduplicacao por sobreposicao.
    List<Candidato> vencedores = desduplicar(candidatos);

    // Fase 3: agrupamento por regra.
    List<Achado> achados = agrupar(vencedores);

    return new ResultadoVarredura(achados,
                                  completa,
                                  motivo,
                                  alvo.length(),
                                  System.currentTimeMillis() - inicio);
  }

  /**
   * Mantem, de cada grupo de candidatos sobrepostos, apenas o vencedor.
   *
   * <p>Ordena por posicao inicial e varre uma vez, comparando so' com os
   * vencedores ja' aceitos que ainda podem alcancar a posicao corrente. Nao e'
   * o produto cartesiano ingenuo (que seria O(n^2) e, num arquivo com cem mil
   * achados, sozinho estouraria o orcamento de tempo que a fase 1 respeitou).
   */
  private static List<Candidato> desduplicar(List<Candidato> candidatos) {
    if (candidatos.size() < 2) {
      return candidatos;
    }
    List<Candidato> ordenados = new ArrayList<>(candidatos);
    ordenados.sort(Comparator
        .comparingInt((Candidato c) -> c.ocorrencia.getInicio())
        .thenComparingInt(c -> -c.ocorrencia.getComprimento()));

    List<Candidato> aceitos = new ArrayList<>(ordenados.size());
    for (Candidato candidato : ordenados) {
      boolean absorvido = false;
      // Anda de tras para frente e para assim que os aceitos ficam longe demais
      // para sobrepor -- as ocorrencias estao ordenadas por inicio.
      for (int i = aceitos.size() - 1; i >= 0; i--) {
        Candidato aceito = aceitos.get(i);
        if (aceito.ocorrencia.getFim() <= candidato.ocorrencia.getInicio()) {
          // INVARIANTE: os aceitos nao se sobrepoem entre si e estao em ordem
          // crescente de inicio -- logo tambem em ordem crescente de fim. Se
          // este ja' termina antes do candidato comecar, todos os anteriores
          // terminam antes ainda. Nao ha' o que procurar.
          break;
        }
        if (!aceito.ocorrencia.sobrepoe(candidato.ocorrencia)) {
          continue;
        }
        if (venceu(candidato, aceito)) {
          aceitos.set(i, candidato);
        }
        absorvido = true;
        break;
      }
      if (!absorvido) {
        aceitos.add(candidato);
      }
    }
    return aceitos;
  }

  /** Regra de desempate documentada no javadoc da classe. */
  private static boolean venceu(Candidato desafiante, Candidato titular) {
    int porSeveridade = desafiante.regra.getSeveridade()
                                        .compareTo(titular.regra.getSeveridade());
    if (porSeveridade != 0) {
      return porSeveridade > 0;
    }
    return ordemNoCatalogo(desafiante.regra) < ordemNoCatalogo(titular.regra);
  }

  private static int ordemNoCatalogo(Regra regra) {
    List<Regra> catalogo = RegrasSensiveis.regras();
    for (int i = 0; i < catalogo.size(); i++) {
      if (catalogo.get(i) == regra) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }

  /**
   * Junta os vencedores por rotulo. Sai ordenado por severidade decrescente e,
   * dentro da mesma severidade, pela ordem do catalogo -- para o administrador
   * ler o que importa na primeira linha, nao na decima.
   */
  private static List<Achado> agrupar(List<Candidato> vencedores) {
    Map<String, List<Ocorrencia>> porRotulo = new LinkedHashMap<>();
    Map<String, Severidade> severidades = new LinkedHashMap<>();
    Map<String, Integer> ordens = new LinkedHashMap<>();

    List<Candidato> emOrdemDeTexto = new ArrayList<>(vencedores);
    emOrdemDeTexto.sort(Comparator.comparingInt(c -> c.ocorrencia.getInicio()));

    for (Candidato candidato : emOrdemDeTexto) {
      String rotulo = candidato.regra.getRotulo();
      porRotulo.computeIfAbsent(rotulo, r -> new ArrayList<>()).add(candidato.ocorrencia);
      severidades.putIfAbsent(rotulo, candidato.regra.getSeveridade());
      ordens.putIfAbsent(rotulo, ordemNoCatalogo(candidato.regra));
    }

    List<Achado> achados = new ArrayList<>(porRotulo.size());
    for (Map.Entry<String, List<Ocorrencia>> entrada : porRotulo.entrySet()) {
      achados.add(new Achado(entrada.getKey(),
                             severidades.get(entrada.getKey()),
                             entrada.getValue()));
    }
    achados.sort(Comparator
        .comparing((Achado a) -> a.getSeveridade(), Comparator.reverseOrder())
        .thenComparingInt(a -> ordens.get(a.getRotulo())));
    return achados;
  }

  /** Encadeia motivos de incompletude sem perder nenhum. */
  private static String juntar(String anterior, String novo) {
    return anterior == null || anterior.isEmpty() ? novo : anterior + "; " + novo;
  }

  /** Par regra+ocorrencia, vivo so' entre as fases 1 e 3. */
  private static final class Candidato {
    private final Regra regra;
    private final Ocorrencia ocorrencia;

    Candidato(Regra regra, Ocorrencia ocorrencia) {
      this.regra = regra;
      this.ocorrencia = ocorrencia;
    }
  }
}
