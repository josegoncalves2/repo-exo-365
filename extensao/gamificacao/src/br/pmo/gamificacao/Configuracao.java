package br.pmo.gamificacao;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Os valores cadastrados para UM conector, sabendo quais deles sao segredo.
 *
 * <p>POR QUE ELA CONHECE OS CAMPOS DO CONECTOR. A configuracao e' construida a
 * partir de {@link Conector#campos()}, e nao de um mapa solto. Assim ela sabe,
 * sem adivinhar por nome, quais chaves sao credencial -- e e' esse conhecimento
 * que permite ao {@link #toString()} mascarar o que precisa ser mascarado. Um
 * mapa solto teria de decidir por heuristica ("a chave contem 'token'?"), e
 * heuristica erra justamente no caso novo, que e' quando o vazamento acontece.
 *
 * <p>POR QUE VALOR EM BRANCO E' O MESMO QUE AUSENTE. Formulario de portal manda
 * string vazia para campo nao preenchido, e {@code exo.properties} sem valor
 * definido resolve para "". Se vazio contasse como preenchido,
 * {@link #estaConfigurado} diria "configurado" para um conector sem token
 * nenhum, a chamada sairia com cabecalho vazio e voltaria 401 -- exibindo
 * "falhou" onde a verdade e' "nunca foi configurado".
 */
public final class Configuracao {

  private final Map<String, String> valores;

  private final Set<String> chavesSegredo;

  private final Set<String> chavesObrigatorias;

  private Configuracao(Map<String, String> valores, Set<String> chavesSegredo,
      Set<String> chavesObrigatorias) {
    this.valores = Collections.unmodifiableMap(valores);
    this.chavesSegredo = Collections.unmodifiableSet(chavesSegredo);
    this.chavesObrigatorias = Collections.unmodifiableSet(chavesObrigatorias);
  }

  /**
   * Monta a configuracao de um conector a partir dos valores cadastrados.
   *
   * <p>Chave desconhecida -- que o conector nao declarou -- e' DESCARTADA, nao
   * guardada. Motivo: impede que um valor cadastrado para um conector seja lido
   * por engano por outro, e impede que um campo removido do codigo continue
   * viajando em silencio.
   */
  public static Configuracao de(Conector conector, Map<String, String> brutos) {
    Objects.requireNonNull(conector, "conector");
    Map<String, String> aceitos = new LinkedHashMap<>();
    Set<String> segredos = new TreeSet<>();
    Set<String> obrigatorios = new TreeSet<>();
    List<CampoConfig> campos = conector.campos();
    for (CampoConfig campo : campos) {
      if (campo.segredo()) {
        segredos.add(campo.chave());
      }
      if (campo.obrigatorio()) {
        obrigatorios.add(campo.chave());
      }
      String valor = brutos == null ? null : brutos.get(campo.chave());
      if (valor != null && !valor.isBlank()) {
        aceitos.put(campo.chave(), valor.trim());
      }
    }
    return new Configuracao(aceitos, segredos, obrigatorios);
  }

  /** Configuracao de um conector recem-instalado: nenhum campo preenchido. */
  public static Configuracao vazia(Conector conector) {
    return de(conector, Collections.emptyMap());
  }

  /**
   * Valor de um campo NAO segredo.
   *
   * @throws IllegalArgumentException se a chave for de um campo marcado como
   *         segredo. E' barreira de proposito: forca quem precisa da credencial
   *         a passar por {@link #segredo(String)} e, portanto, por
   *         {@link Segredo#revelar()}, que e' o ponto auditavel.
   */
  public String valor(String chave) {
    if (chavesSegredo.contains(chave)) {
      throw new IllegalArgumentException(
          "campo '" + chave + "' e' segredo: use segredo(\"" + chave + "\")");
    }
    return valores.get(chave);
  }

  /** Igual a {@link #valor}, mas com alternativa quando ausente. */
  public String valorOu(String chave, String alternativa) {
    String v = valor(chave);
    return v == null ? alternativa : v;
  }

  /** Credencial envelopada. Devolve {@link Segredo#ausente()} se nao cadastrada. */
  public Segredo segredo(String chave) {
    if (!chavesSegredo.contains(chave)) {
      throw new IllegalArgumentException("campo '" + chave + "' nao foi declarado como segredo");
    }
    return Segredo.de(valores.get(chave));
  }

  /** {@code true} se a chave tem valor nao vazio, seja segredo ou nao. */
  public boolean tem(String chave) {
    return valores.containsKey(chave);
  }

  /** Chaves obrigatorias que continuam sem valor. Vazio = pronto para uso. */
  public Set<String> faltando() {
    Set<String> faltam = new TreeSet<>();
    for (String chave : chavesObrigatorias) {
      if (!valores.containsKey(chave)) {
        faltam.add(chave);
      }
    }
    return faltam;
  }

  /** Nenhum campo obrigatorio em falta. */
  public boolean completa() {
    return faltando().isEmpty();
  }

  /**
   * Representacao para log e depuracao, com TODO segredo mascarado.
   *
   * <p>Esta e' a linha de defesa que o {@link Segredo} sozinho nao da': aqui o
   * mascaramento vale mesmo para quem imprimir a configuracao inteira de uma
   * vez, que e' exatamente o que se faz ao depurar.
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Configuracao{");
    boolean primeiro = true;
    for (Map.Entry<String, String> e : valores.entrySet()) {
      if (!primeiro) {
        sb.append(", ");
      }
      primeiro = false;
      sb.append(e.getKey()).append('=');
      sb.append(chavesSegredo.contains(e.getKey()) ? "***" : e.getValue());
    }
    Set<String> faltam = faltando();
    if (!faltam.isEmpty()) {
      sb.append(primeiro ? "" : ", ").append("faltando=").append(faltam);
    }
    return sb.append('}').toString();
  }
}
