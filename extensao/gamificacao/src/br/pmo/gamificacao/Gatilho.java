package br.pmo.gamificacao;

import java.util.Objects;

/**
 * Um evento que um conector sabe emitir e que vale pontos.
 *
 * <p>POR QUE O ID E' SEPARADO DO ROTULO: o id viaja no payload do provedor
 * externo e no banco de realizacoes; o rotulo aparece na tela e um dia sera'
 * traduzido. Se fossem o mesmo campo, traduzir a tela renomearia o evento e
 * quebraria todo o historico de pontuacao ja' gravado.
 */
public final class Gatilho {

  private final String id;

  private final String rotulo;

  private final Categoria categoria;

  public Gatilho(String id, String rotulo, Categoria categoria) {
    this.id = exigirTexto(id, "id");
    this.rotulo = exigirTexto(rotulo, "rotulo");
    this.categoria = Objects.requireNonNull(categoria, "categoria");
  }

  private static String exigirTexto(String valor, String campo) {
    if (valor == null || valor.isBlank()) {
      throw new IllegalArgumentException("gatilho sem " + campo);
    }
    return valor;
  }

  public String id() {
    return id;
  }

  public String rotulo() {
    return rotulo;
  }

  public Categoria categoria() {
    return categoria;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Gatilho)) {
      return false;
    }
    return id.equals(((Gatilho) o).id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Gatilho[" + id + " / " + categoria.name() + "]";
  }
}
