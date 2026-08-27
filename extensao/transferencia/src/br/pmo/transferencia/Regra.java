package br.pmo.transferencia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import br.pmo.mfa.Zona;

/**
 * Uma regra de transferencia: condicoes que precisam TODAS bater, e o que
 * fazer quando batem.
 *
 * <h2>Por que E' entre condicoes e OU dentro de cada condicao</h2>
 *
 * Uma regra como "negar .pst para quem NAO e' do grupo TI, quando vier de fora
 * da rede interna" se escreve naturalmente como uma conjuncao de tres testes,
 * cada um deles admitindo varios valores. Se as condicoes fossem OU entre si,
 * a mesma regra precisaria ser quebrada em varias e o administrador teria de
 * fazer a combinatoria na cabeca ; e' assim que se escreve, sem perceber, uma
 * regra que libera tudo.
 *
 * <p>Condicao NAO declarada significa "nao me importo com isso", nunca "nao
 * casa". Uma regra sem condicao alguma casa com todo pedido ; e' o jeito de
 * escrever a regra final de fecho.
 *
 * <h2>Negacao explicita</h2>
 *
 * {@code gruposExcluidos} existe porque "todos MENOS o grupo X" e' o formato
 * real de quase toda politica de prefeitura. Sem ele, escrever essa regra
 * exigiria listar todos os grupos ; e a lista envelhece no dia em que alguem
 * cria um grupo novo, silenciosamente deixando gente de fora da regra.
 */
public final class Regra {

  /** O que a regra determina quando casa. */
  public enum Efeito {
    PERMITIR, NEGAR
  }

  private final String nome;
  private final Efeito efeito;
  private final Set<Pedido.Operacao> operacoes;
  private final Set<String> grupos;
  private final Set<String> gruposExcluidos;
  private final Set<String> extensoes;
  private final long tamanhoMinimoBytes;
  private final List<Zona> zonas;
  private final String motivo;

  private Regra(Construtor c) {
    this.nome = c.nome;
    this.efeito = c.efeito;
    this.operacoes = imutavel(c.operacoes);
    this.grupos = imutavelTexto(c.grupos);
    this.gruposExcluidos = imutavelTexto(c.gruposExcluidos);
    this.extensoes = imutavelTexto(c.extensoes);
    this.tamanhoMinimoBytes = c.tamanhoMinimoBytes;
    this.zonas = Collections.unmodifiableList(new ArrayList<>(c.zonas));
    this.motivo = c.motivo;
  }

  private static <T> Set<T> imutavel(Set<T> origem) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(origem));
  }

  private static Set<String> imutavelTexto(Set<String> origem) {
    Set<String> copia = new LinkedHashSet<>();
    for (String s : origem) {
      if (s != null && !s.trim().isEmpty()) {
        copia.add(s.trim().toLowerCase(Locale.ROOT));
      }
    }
    return Collections.unmodifiableSet(copia);
  }

  public String getNome() {
    return nome;
  }

  public Efeito getEfeito() {
    return efeito;
  }

  public String getMotivo() {
    return motivo == null || motivo.trim().isEmpty()
           ? "regra '" + nome + "'"
           : motivo;
  }

  /** Todas as condicoes declaradas batem neste pedido? */
  public boolean casa(Pedido pedido) {
    if (pedido == null) {
      return false;
    }

    if (!operacoes.isEmpty() && !operacoes.contains(pedido.getOperacao())) {
      return false;
    }

    // Exclusao e' avaliada ANTES da inclusao: pertencer a um grupo excluido
    // tira o pedido da regra, mesmo que ele tambem pertenca a um incluido.
    // O contrario faria "todos menos TI" nao funcionar para quem esta' em TI
    // e tambem em outro grupo listado.
    if (!gruposExcluidos.isEmpty() && contemAlgum(pedido.getGrupos(), gruposExcluidos)) {
      return false;
    }

    if (!grupos.isEmpty() && !contemAlgum(pedido.getGrupos(), grupos)) {
      return false;
    }

    if (!extensoes.isEmpty() && !extensoes.contains(pedido.getExtensao())) {
      return false;
    }

    if (tamanhoMinimoBytes > 0 && pedido.getTamanhoBytes() < tamanhoMinimoBytes) {
      return false;
    }

    if (!zonas.isEmpty()) {
      String origem = pedido.getEnderecoOrigem();
      if (origem == null) {
        // Origem desconhecida NAO casa uma regra que fala de rede. Se a regra
        // for de PERMITIR, isso e' o comportamento seguro (nao libera pelo
        // desconhecido); se for de NEGAR, quem fecha e' a acao padrao da
        // politica, que nasce restritiva.
        return false;
      }
      boolean bateu = false;
      for (Zona zona : zonas) {
        if (zona.contem(origem)) {
          bateu = true;
          break;
        }
      }
      if (!bateu) {
        return false;
      }
    }

    return true;
  }

  private static boolean contemAlgum(Set<String> doPedido, Set<String> daRegra) {
    for (String grupo : doPedido) {
      if (daRegra.contains(grupo.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return nome + "[" + efeito + "]";
  }

  public static Construtor nomeada(String nome, Efeito efeito) {
    return new Construtor(nome, efeito);
  }

  /** Construtor encadeado. Regra e' objeto de configuracao com muitos campos
   *  opcionais, e um construtor de dez parametros nulos e' onde se troca um
   *  argumento pelo outro sem o compilador reclamar. */
  public static final class Construtor {
    private final String nome;
    private final Efeito efeito;
    private final Set<Pedido.Operacao> operacoes = new LinkedHashSet<>();
    private final Set<String> grupos = new LinkedHashSet<>();
    private final Set<String> gruposExcluidos = new LinkedHashSet<>();
    private final Set<String> extensoes = new LinkedHashSet<>();
    private final List<Zona> zonas = new ArrayList<>();
    private long tamanhoMinimoBytes;
    private String motivo;

    private Construtor(String nome, Efeito efeito) {
      if (nome == null || nome.trim().isEmpty()) {
        throw new IllegalArgumentException("regra sem nome: o nome vai para o log de auditoria");
      }
      if (efeito == null) {
        throw new IllegalArgumentException("regra sem efeito");
      }
      this.nome = nome.trim();
      this.efeito = efeito;
    }

    public Construtor paraOperacoes(Pedido.Operacao... valores) {
      Collections.addAll(operacoes, valores);
      return this;
    }

    public Construtor paraGrupos(String... valores) {
      Collections.addAll(grupos, valores);
      return this;
    }

    public Construtor excetoGrupos(String... valores) {
      Collections.addAll(gruposExcluidos, valores);
      return this;
    }

    public Construtor paraExtensoes(String... valores) {
      for (String v : valores) {
        if (v != null) {
          // Aceita ".pst" e "pst" ; escrever com ponto e' o reflexo natural.
          grupoExtensao(v);
        }
      }
      return this;
    }

    private void grupoExtensao(String v) {
      String limpo = v.trim();
      if (limpo.startsWith(".")) {
        limpo = limpo.substring(1);
      }
      if (!limpo.isEmpty()) {
        extensoes.add(limpo);
      }
    }

    public Construtor aPartirDeBytes(long minimo) {
      this.tamanhoMinimoBytes = minimo;
      return this;
    }

    public Construtor paraZonas(List<Zona> valores) {
      if (valores != null) {
        zonas.addAll(valores);
      }
      return this;
    }

    public Construtor comMotivo(String texto) {
      this.motivo = texto;
      return this;
    }

    public Regra construir() {
      return new Regra(this);
    }
  }
}
