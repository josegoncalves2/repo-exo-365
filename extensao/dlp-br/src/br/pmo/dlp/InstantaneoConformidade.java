package br.pmo.dlp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fotografia imutavel do {@link RelatorioConformidade}: os numeros de um
 * instante, mais a saida em texto e em CSV.
 *
 * <h2>Itens por rotulo e ocorrencias por rotulo sao numeros DIFERENTES</h2>
 * Trezentos CPFs num contracheque nao e' a mesma coisa que um CPF em trezentos
 * documentos. O primeiro e' UM arquivo para tratar; o segundo e' um habito
 * espalhado pelo orgao, e a resposta e' treinamento, nao quarentena. Relatorio
 * que publica so' "300 CPFs" nao deixa distinguir os dois casos, e leva a
 * decisao errada com aparencia de dado. Aqui os dois numeros saem lado a lado.
 *
 * <h2>Defesa contra injecao de formula no CSV</h2>
 * Este arquivo e' feito para ser aberto em planilha por quem audita. Campo que
 * comeca com {@code =}, {@code +}, {@code -} ou {@code @} e' interpretado como
 * FORMULA pelo Excel e pelo LibreOffice, e formula em planilha executa. Como
 * parte do conteudo vem de texto que o proprio sistema montou a partir de
 * entrada externa (nome de arquivo, motivo cru), todo campo e' neutralizado com
 * apostrofo antes do caractere de risco. Custa um caractere e fecha uma porta
 * que ja' foi usada para executar comando na maquina de auditor.
 */
public final class InstantaneoConformidade {

  private final String titulo;

  private final long inicio;

  private final long fim;

  private final int total;

  private final int naoVarridosComAchado;

  private final Map<CategoriaConformidade, Integer> porCategoria;

  private final Map<MotivoNaoVarrido, Integer> porMotivo;

  private final Map<Classificacao, Integer> porClassificacao;

  private final Map<String, Integer> itensPorRotulo;

  private final Map<String, Long> ocorrenciasPorRotulo;

  private final Map<CategoriaConformidade, List<String>> amostrasPorCategoria;

  private final Map<MotivoNaoVarrido, List<String>> amostrasPorMotivo;

  private final List<String> amostrasDeMotivoCru;

  InstantaneoConformidade(String titulo,
                          long inicio,
                          long fim,
                          int total,
                          int naoVarridosComAchado,
                          Map<CategoriaConformidade, Integer> porCategoria,
                          Map<MotivoNaoVarrido, Integer> porMotivo,
                          Map<Classificacao, Integer> porClassificacao,
                          Map<String, Integer> itensPorRotulo,
                          Map<String, Long> ocorrenciasPorRotulo,
                          Map<CategoriaConformidade, List<String>> amostrasPorCategoria,
                          Map<MotivoNaoVarrido, List<String>> amostrasPorMotivo,
                          List<String> amostrasDeMotivoCru) {
    this.titulo = titulo;
    this.inicio = inicio;
    this.fim = fim;
    this.total = total;
    this.naoVarridosComAchado = naoVarridosComAchado;
    this.porCategoria = Collections.unmodifiableMap(new EnumMap<>(porCategoria));
    this.porMotivo = Collections.unmodifiableMap(new EnumMap<>(porMotivo));
    this.porClassificacao = Collections.unmodifiableMap(new EnumMap<>(porClassificacao));
    this.itensPorRotulo = Collections.unmodifiableMap(new LinkedHashMap<>(itensPorRotulo));
    this.ocorrenciasPorRotulo = Collections.unmodifiableMap(new LinkedHashMap<>(ocorrenciasPorRotulo));
    this.amostrasPorCategoria = copiarProfundo(amostrasPorCategoria, CategoriaConformidade.class);
    this.amostrasPorMotivo = copiarProfundo(amostrasPorMotivo, MotivoNaoVarrido.class);
    this.amostrasDeMotivoCru = Collections.unmodifiableList(new ArrayList<>(amostrasDeMotivoCru));
  }

  private static <C extends Enum<C>> Map<C, List<String>> copiarProfundo(Map<C, List<String>> origem,
                                                                        Class<C> tipo) {
    Map<C, List<String>> copia = new EnumMap<>(tipo);
    for (Map.Entry<C, List<String>> entrada : origem.entrySet()) {
      copia.put(entrada.getKey(), Collections.unmodifiableList(new ArrayList<>(entrada.getValue())));
    }
    return Collections.unmodifiableMap(copia);
  }

  // ===========================================================================
  // Numeros
  // ===========================================================================

  public String getTitulo() {
    return titulo;
  }

  /** Itens registrados. E' o denominador de todo percentual deste relatorio. */
  public int getTotal() {
    return total;
  }

  public int getQuantidade(CategoriaConformidade categoria) {
    return porCategoria.getOrDefault(categoria, 0);
  }

  public int getQuantidade(MotivoNaoVarrido motivo) {
    return porMotivo.getOrDefault(motivo, 0);
  }

  public int getQuantidade(Classificacao classificacao) {
    return porClassificacao.getOrDefault(classificacao, 0);
  }

  /**
   * Itens NAO varridos que, mesmo assim, ja' mostraram dado sensivel no pedaco
   * lido. E' o balde mais grave do acervo e por isso tem numero proprio.
   */
  public int getNaoVarridosComAchado() {
    return naoVarridosComAchado;
  }

  /** Percentual do acervo, 0 a 100. Devolve 0 quando nada foi registrado. */
  public double getPercentual(CategoriaConformidade categoria) {
    return total == 0 ? 0d : (getQuantidade(categoria) * 100d) / total;
  }

  public double getPercentual(MotivoNaoVarrido motivo) {
    return total == 0 ? 0d : (getQuantidade(motivo) * 100d) / total;
  }

  /** Quantos ITENS contem cada rotulo. */
  public Map<String, Integer> getItensPorRotulo() {
    return itensPorRotulo;
  }

  /** Quantas OCORRENCIAS de cada rotulo ha' no acervo inteiro. */
  public Map<String, Long> getOcorrenciasPorRotulo() {
    return ocorrenciasPorRotulo;
  }

  public List<String> getAmostras(CategoriaConformidade categoria) {
    return amostrasPorCategoria.getOrDefault(categoria, Collections.<String>emptyList());
  }

  public List<String> getAmostras(MotivoNaoVarrido motivo) {
    return amostrasPorMotivo.getOrDefault(motivo, Collections.<String>emptyList());
  }

  /** Motivos crus que nao foram reconhecidos. Lista nao vazia = deriva a olhar. */
  public List<String> getAmostrasDeMotivoCru() {
    return amostrasDeMotivoCru;
  }

  // ===========================================================================
  // Saidas
  // ===========================================================================

  /** Relatorio legivel, para tela e para anexo de e-mail. */
  public String emTexto() {
    StringBuilder sb = new StringBuilder();
    String risco = "=".repeat(70);
    sb.append(risco).append('\n').append(titulo).append('\n');
    sb.append("Periodo: ").append(quando(inicio)).append("  ate  ").append(quando(fim)).append('\n');
    sb.append("Itens analisados: ").append(total).append('\n');
    sb.append(risco).append("\n\n");

    sb.append("SITUACAO DO ACERVO\n");
    for (CategoriaConformidade categoria : CategoriaConformidade.values()) {
      sb.append(String.format(Locale.ROOT, "  %-14s %8d  %6.2f%%%n",
                              categoria.getRotulo(),
                              getQuantidade(categoria),
                              getPercentual(categoria)));
    }
    if (naoVarridosComAchado > 0) {
      sb.append(String.format(Locale.ROOT,
                              "%n  ATENCAO: %d item(ns) NAO varridos por inteiro ja' mostraram dado%n"
                              + "  sensivel no pedaco que deu para ler. Sao os mais graves do acervo.%n",
                              naoVarridosComAchado));
    }

    int naoVarridos = getQuantidade(CategoriaConformidade.NAO_VARRIDO);
    if (naoVarridos > 0) {
      sb.append("\nPOR QUE NAO FOI VARRIDO — e o que fazer com cada linha\n");
      for (MotivoNaoVarrido motivo : MotivoNaoVarrido.values()) {
        int n = getQuantidade(motivo);
        if (n == 0) {
          continue;
        }
        sb.append(String.format(Locale.ROOT, "  %-46s %7d  %6.2f%%  (%s)%n",
                                motivo.getRotulo(), n, getPercentual(motivo),
                                motivo.getEncaminhamento()));
      }
      if (!amostrasDeMotivoCru.isEmpty()) {
        sb.append("\n  Motivos NAO reconhecidos (a classificacao pode ter derivado):\n");
        for (String cru : amostrasDeMotivoCru) {
          sb.append("    - ").append(cru).append('\n');
        }
      }
    }

    if (!itensPorRotulo.isEmpty()) {
      sb.append("\nO QUE FOI ENCONTRADO\n");
      sb.append(String.format(Locale.ROOT, "  %-24s %10s %14s%n", "Tipo de dado", "Itens", "Ocorrencias"));
      for (Map.Entry<String, Integer> entrada : itensPorRotulo.entrySet()) {
        sb.append(String.format(Locale.ROOT, "  %-24s %10d %14d%n",
                                entrada.getKey(),
                                entrada.getValue(),
                                ocorrenciasPorRotulo.getOrDefault(entrada.getKey(), 0L)));
      }
      sb.append("\n  Itens e ocorrencias sao numeros diferentes de proposito: 300 CPFs em UM\n");
      sb.append("  contracheque e' um arquivo para tratar; 1 CPF em 300 documentos e' um\n");
      sb.append("  habito espalhado, e a resposta e' treinamento, nao quarentena.\n");
    }

    boolean temClassificacao = false;
    for (Classificacao c : Classificacao.values()) {
      if (getQuantidade(c) > 0) {
        temClassificacao = true;
        break;
      }
    }
    if (temClassificacao) {
      sb.append("\nCLASSIFICACAO ATRIBUIDA (so' do que foi lido por inteiro)\n");
      for (Classificacao c : Classificacao.values()) {
        if (getQuantidade(c) > 0) {
          sb.append(String.format(Locale.ROOT, "  %-12s %8d%n", c.name(), getQuantidade(c)));
        }
      }
      int semClassificacao = getQuantidade(CategoriaConformidade.NAO_VARRIDO);
      if (semClassificacao > 0) {
        sb.append(String.format(Locale.ROOT,
                                "  %-12s %8d  (nao varrido nao tem classificacao: tem pendencia)%n",
                                "(sem)", semClassificacao));
      }
    }

    sb.append("\nNENHUM VALOR DETECTADO APARECE NESTE RELATORIO — so' rotulo e contagem.\n");
    return sb.toString();
  }

  /**
   * Mesmas contas, em CSV, para planilha. Uma linha por metrica, com as colunas
   * {@code secao;chave;quantidade;percentual;observacao} -- formato longo, que
   * dinamiza sem retrabalho.
   */
  public String emCsv() {
    StringBuilder sb = new StringBuilder();
    sb.append("secao;chave;quantidade;percentual;observacao\n");

    for (CategoriaConformidade categoria : CategoriaConformidade.values()) {
      linha(sb, "situacao", categoria.getRotulo(), getQuantidade(categoria),
            getPercentual(categoria), "");
    }
    linha(sb, "situacao", "Nao varridos que ja' mostraram achado", naoVarridosComAchado,
          total == 0 ? 0d : (naoVarridosComAchado * 100d) / total, "os mais graves do acervo");

    for (MotivoNaoVarrido motivo : MotivoNaoVarrido.values()) {
      if (getQuantidade(motivo) > 0) {
        linha(sb, "motivo", motivo.getRotulo(), getQuantidade(motivo),
              getPercentual(motivo), motivo.getEncaminhamento());
      }
    }
    for (Map.Entry<String, Integer> entrada : itensPorRotulo.entrySet()) {
      linha(sb, "itens_por_tipo", entrada.getKey(), entrada.getValue(),
            total == 0 ? 0d : (entrada.getValue() * 100d) / total, "");
    }
    for (Map.Entry<String, Long> entrada : ocorrenciasPorRotulo.entrySet()) {
      linha(sb, "ocorrencias_por_tipo", entrada.getKey(), entrada.getValue(), -1d, "");
    }
    for (Classificacao c : Classificacao.values()) {
      if (getQuantidade(c) > 0) {
        linha(sb, "classificacao", c.name(), getQuantidade(c),
              total == 0 ? 0d : (getQuantidade(c) * 100d) / total, "");
      }
    }
    for (String cru : amostrasDeMotivoCru) {
      linha(sb, "motivo_nao_reconhecido", cru, 0, -1d, "classificacao pode ter derivado");
    }
    return sb.toString();
  }

  private void linha(StringBuilder sb, String secao, String chave, long quantidade,
                     double percentual, String observacao) {
    sb.append(campo(secao)).append(';')
      .append(campo(chave)).append(';')
      .append(quantidade).append(';')
      .append(percentual < 0 ? "" : String.format(Locale.ROOT, "%.2f", percentual)).append(';')
      .append(campo(observacao)).append('\n');
  }

  /**
   * Neutraliza o campo para planilha. Duas coisas, e as duas sao necessarias:
   * escapa aspas e ponto-e-virgula (senao a coluna quebra), e prefixa apostrofo
   * quando o texto comeca com caractere que a planilha lê como inicio de
   * FORMULA.
   */
  static String campo(String bruto) {
    if (bruto == null || bruto.isEmpty()) {
      return "";
    }
    String texto = bruto.replace("\r", " ").replace("\n", " ");
    char primeiro = texto.charAt(0);
    if (primeiro == '=' || primeiro == '+' || primeiro == '-' || primeiro == '@'
        || primeiro == '\t') {
      texto = "'" + texto;
    }
    if (texto.indexOf(';') >= 0 || texto.indexOf('"') >= 0) {
      texto = '"' + texto.replace("\"", "\"\"") + '"';
    }
    return texto;
  }

  private static String quando(long instante) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(instante));
  }

  @Override
  public String toString() {
    return "InstantaneoConformidade[" + total + " itens, "
           + getQuantidade(CategoriaConformidade.NAO_VARRIDO) + " nao varridos]";
  }
}
