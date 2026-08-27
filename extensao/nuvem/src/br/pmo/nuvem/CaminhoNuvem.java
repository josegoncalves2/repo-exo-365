package br.pmo.nuvem;

/**
 * Caminho de arquivo remoto, normalizado e VALIDADO.
 *
 * <p><b>POR QUE ESTA CLASSE EXISTE.</b> O caminho vem do servidor Nextcloud por
 * PROPFIND -- ou seja, de um servidor possivelmente hostil ou comprometido -- e
 * vira caminho de no' no JCR da plataforma. Sem validacao, um caminho com
 * {@code ..} escaparia da raiz do drive; barra dupla confunde o parser do
 * WebDAV; byte nulo corrompe qualquer API de arquivo. Um unico ponto de entrada
 * para caminhos vindos da rede fecha todas essas portas de uma vez, em vez de
 * cada consumidor lembrar de validar.
 *
 * <p><b>COMO FUNCIONA.</b> {@link #de(String)} valida e normaliza em um passo:
 *
 * <ol>
 *   <li>rejeita nulo, vazio e caminho que nao comeca com {@code /};</li>
 *   <li>rejeita {@code ..} em qualquer posicao (nao so' no inicio): um
 *       componente que seja exatamente {@code ..} ou {@code .} e' tentativa de
 *       escapar;</li>
 *   <li>rejeita byte nulo ({@code \u0000}) e caractere de controle;</li>
 *   <li>rejeita barra dupla {@code //} -- servidores WebDAV divergem no
 *       tratamento, e divergencia entre servidor e cliente e' bug esperando
 *       acontecer;</li>
 *   <li>rejeita componente de caminho vazio (resultado de barra dupla) e
 *       componente com barra invertida {@code \} (separador de outro sistema,
 *       que no destino vira parte do NOME -- um servidor Windows responderia
 *       {@code pasta\sub} e o JCR criaria um arquivo chamado {@code pasta\sub});</li>
 *   <li>rejeita espaco em BRANCO em componente de nome -- caminho vindo de
 *       servidor nao confiavel nao pode criar no' com nome invisivel;</li>
 *   <li>aplica {@code normalize()} do Path JDK para colapsar {@code /a/./b}
 *       (mantendo barra como separador canonico).</li>
 * </ol>
 *
 * <p><b>REGRA DE FALHA.</b> Caminho invalido LANCA excecao. Nunca devolve
 * caminho "consertado" por suposicao: "consertar" {@code ../../etc} virando
 * {@code /etc} seria aceitar o ataque com outra cara.
 */
public final class CaminhoNuvem {

  private final String caminho;

  private CaminhoNuvem(String caminho) {
    this.caminho = caminho;
  }

  /**
   * Valida, normaliza e devolve o caminho.
   *
   * @throws IllegalArgumentException quando o caminho nao e' seguro
   */
  public static CaminhoNuvem de(String bruto) {
    if (bruto == null) {
      throw new IllegalArgumentException("caminho nulo");
    }
    String c = bruto.trim();
    if (c.isEmpty()) {
      throw new IllegalArgumentException("caminho vazio");
    }
    if (!c.startsWith("/")) {
      throw new IllegalArgumentException("caminho precisa comecar com /: '" + bruto + "'");
    }
    if (c.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("caminho com byte nulo: recusado");
    }
    if (c.indexOf("//") >= 0) {
      throw new IllegalArgumentException("caminho com barra dupla: recusado");
    }
    for (int i = 0; i < c.length(); i++) {
      char ch = c.charAt(i);
      if (ch < 0x20) {
        throw new IllegalArgumentException("caminho com caractere de controle: recusado");
      }
      if (ch == '\\') {
        throw new IllegalArgumentException("caminho com barra invertida: recusado");
      }
    }
    // Divide em componentes e valida cada um. O split de "/a/b" produz um
    // componente vazio no indice 0, que e' a propria barra inicial -- so' esse
    // e' legitimo; qualquer outro vazio significa barra dupla ou barra final.
    String[] componentes = c.split("/");
    for (int i = 0; i < componentes.length; i++) {
      String componente = componentes[i];
      if (componente.isEmpty()) {
        if (i == 0) {
          continue; // a barra inicial
        }
        throw new IllegalArgumentException("caminho com barra final ou componente vazio: recusado");
      }
      if (componente.equals(".") || componente.equals("..")) {
        throw new IllegalArgumentException("caminho com componente '" + componente
            + "': tentativa de escapar da raiz");
      }
      if (componente.indexOf(' ') >= 0 || componente.indexOf('\u00a0') >= 0) {
        throw new IllegalArgumentException("caminho com espaco em componente de nome: recusado");
      }
    }
    // Path do JDK colapsa /a/./b; ja' validamos que nao ha' . ou ..
    // remanescente, entao normalize() nao pode mudar o significado.
    String normalizado = java.nio.file.Paths.get(c).normalize().toString();
    // Path no Linux usa '/'; se o SO de build for Windows, corrige.
    if (normalizado.indexOf('\\') >= 0) {
      normalizado = normalizado.replace('\\', '/');
    }
    if (!normalizado.startsWith("/")) {
      normalizado = "/" + normalizado;
    }
    return new CaminhoNuvem(normalizado);
  }

  /** O caminho da RAIZ do drive ({@code /}). */
  public static CaminhoNuvem raiz() {
    return new CaminhoNuvem("/");
  }

  /** Verdadeiro quando o caminho e' exatamente a raiz. */
  public boolean ehRaiz() {
    return "/".equals(caminho);
  }

  /** O caminho normalizado, com {@code /} como separador. */
  public String caminho() {
    return caminho;
  }

  /** O ultimo componente (nome do arquivo ou pasta). Raiz devolve vazio. */
  public String nome() {
    if (ehRaiz()) {
      return "";
    }
    int ultima = caminho.lastIndexOf('/');
    return caminho.substring(ultima + 1);
  }

  /** O caminho da pasta que CONTEM este caminho. Raiz devolve raiz. */
  public String pai() {
    if (ehRaiz()) {
      return "/";
    }
    int ultima = caminho.lastIndexOf('/');
    return ultima == 0 ? "/" : caminho.substring(0, ultima);
  }

  /** Concatena um nome de arquivo/pasta a este caminho, validando o resultado. */
  public CaminhoNuvem juntar(String nome) {
    if (nome == null || nome.isEmpty()) {
      throw new IllegalArgumentException("nome vazio");
    }
    if (nome.indexOf('/') >= 0 || nome.indexOf('\\') >= 0 || nome.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("nome com separador ou byte nulo: recusado");
    }
    if (nome.equals(".") || nome.equals("..")) {
      throw new IllegalArgumentException("nome '.', '..' ou com espaco: recusado");
    }
    if (nome.indexOf(' ') >= 0) {
      throw new IllegalArgumentException("nome com espaco: recusado");
    }
    String base = ehRaiz() ? "" : caminho;
    return de(base + "/" + nome);
  }

  @Override
  public boolean equals(Object outro) {
    return outro instanceof CaminhoNuvem
        && ((CaminhoNuvem) outro).caminho.equals(caminho);
  }

  @Override
  public int hashCode() {
    return caminho.hashCode();
  }

  @Override
  public String toString() {
    return caminho;
  }
}
