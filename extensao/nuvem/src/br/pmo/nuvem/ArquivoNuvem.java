package br.pmo.nuvem;

/**
 * Modelo normalizado de um arquivo ou pasta do drive remoto.
 *
 * <p><b>POR QUE NAO USAR A CLASSE DA PLATAFORMA AQUI.</b> Este modelo vive no
 * nucleo puro JDK, que compila e se prova fora do container. A interface
 * {@code CloudFile} da eXo exige JCR e servicos da plataforma -- depender dela
 * no nucleo quebraria a prova no host. O adaptador eXo traduz este modelo para
 * o da plataforma na borda.
 *
 * <p><b>CAMPO CRITICO: {@link #getEtag()}.</b> E' a base da politica de
 * conflito e da sincronizacao: se o etag do servidor mudou e o arquivo local
 * tambem, houve edicao concorrente. Servidor que nao devolve etag (raro no
 * WebDAV moderno, possivel em implementacoes antigas) NAO pode ser tratado
 * como "nunca mudou": {@code etag} nulo dispara a politica de conflito com
 * criterio por tamanho/data, em vez de silenciosamente sobrescrever.
 *
 * <p>Imutavel.
 */
public final class ArquivoNuvem {

  private final String id;
  private final String nome;
  private final CaminhoNuvem caminho;
  private final long tamanho;
  private final long modificadoEm;
  private final String etag;
  private final String mime;
  private final boolean pasta;

  public ArquivoNuvem(String id, String nome, CaminhoNuvem caminho, long tamanho,
                      long modificadoEm, String etag, String mime, boolean pasta) {
    if (nome == null || nome.isEmpty()) {
      throw new IllegalArgumentException("nome de arquivo vazio");
    }
    if (caminho == null) {
      throw new IllegalArgumentException("caminho nulo");
    }
    if (tamanho < 0) {
      throw new IllegalArgumentException("tamanho negativo");
    }
    this.id = id;
    this.nome = nome;
    this.caminho = caminho;
    this.tamanho = tamanho;
    this.modificadoEm = modificadoEm;
    this.etag = etag;
    this.mime = mime;
    this.pasta = pasta;
  }

  /** Identificador do provedor remoto; pode ser nulo se o servidor nao da'. */
  public String getId() {
    return id;
  }

  public String getNome() {
    return nome;
  }

  /** Caminho completo dentro do drive, ja' validado. */
  public CaminhoNuvem getCaminho() {
    return caminho;
  }

  public long getTamanho() {
    return tamanho;
  }

  /** Milissegundos desde a epoch. 0 quando o servidor nao informou. */
  public long getModificadoEm() {
    return modificadoEm;
  }

  /** ETag do servidor; nulo quando o servidor nao informou (dispara conflito
   *  por criterio secundario, nunca sobrescrita cega). */
  public String getEtag() {
    return etag;
  }

  public String getMime() {
    return mime;
  }

  public boolean ehPasta() {
    return pasta;
  }

  @Override
  public String toString() {
    return "ArquivoNuvem[" + (pasta ? "pasta " : "arquivo ") + caminho
        + (etag == null ? "" : " etag=" + etag) + "]";
  }
}
