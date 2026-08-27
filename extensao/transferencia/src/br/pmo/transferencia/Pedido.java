package br.pmo.transferencia;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Um pedido de transferencia de arquivo, com tudo o que a politica precisa
 * saber para decidir. Imutavel.
 *
 * <p>Existe como objeto proprio, e nao como um punhado de parametros soltos,
 * por dois motivos praticos: a politica fica testavel sem servidor nenhum, e
 * acrescentar um criterio novo (classificacao, horario, dispositivo) nao muda
 * a assinatura de nada.
 *
 * <p><b>Nome do arquivo NAO e' fonte de verdade sobre o conteudo.</b> A
 * extensao vem do nome, e o nome vem do usuario. Uma regra por extensao serve
 * para POLITICA ADMINISTRATIVA ("nao se baixa .pst desta pasta"), nao para
 * seguranca de conteudo ; quem quer burlar renomeia. Seguranca de conteudo e'
 * o DLP, que le os bytes.
 */
public final class Pedido {

  private final String usuario;
  private final Set<String> grupos;
  private final String uri;
  private final String nomeArquivo;
  private final long tamanhoBytes;
  private final String enderecoOrigem;
  private final Operacao operacao;

  /** O que se esta' tentando fazer com o arquivo. */
  public enum Operacao {
    BAIXAR, COMPARTILHAR, VISUALIZAR
  }

  public Pedido(String usuario,
                Set<String> grupos,
                String uri,
                String nomeArquivo,
                long tamanhoBytes,
                String enderecoOrigem,
                Operacao operacao) {
    this.usuario = usuario;
    Set<String> copia = new LinkedHashSet<>();
    if (grupos != null) {
      for (String grupo : grupos) {
        if (grupo != null && !grupo.trim().isEmpty()) {
          copia.add(grupo.trim());
        }
      }
    }
    this.grupos = Collections.unmodifiableSet(copia);
    this.uri = uri;
    this.nomeArquivo = nomeArquivo;
    this.tamanhoBytes = tamanhoBytes;
    this.enderecoOrigem = enderecoOrigem;
    this.operacao = operacao == null ? Operacao.BAIXAR : operacao;
  }

  public String getUsuario() {
    return usuario;
  }

  public Set<String> getGrupos() {
    return grupos;
  }

  public String getUri() {
    return uri;
  }

  public String getNomeArquivo() {
    return nomeArquivo;
  }

  public long getTamanhoBytes() {
    return tamanhoBytes;
  }

  public String getEnderecoOrigem() {
    return enderecoOrigem;
  }

  public Operacao getOperacao() {
    return operacao;
  }

  /**
   * Extensao em minusculas, SEM o ponto. Vazio quando nao ha'.
   *
   * <p>Considera apenas o ultimo ponto: {@code relatorio.2026.xlsx} devolve
   * {@code xlsx}. E ignora ponto no fim ({@code arquivo.}) e nome que comeca
   * com ponto e nao tem extensao ({@code .gitignore}), que sao os dois casos
   * em que a leitura ingenua devolve lixo.
   */
  public String getExtensao() {
    if (nomeArquivo == null) {
      return "";
    }
    String nome = nomeArquivo.trim();
    int ponto = nome.lastIndexOf('.');
    if (ponto <= 0 || ponto == nome.length() - 1) {
      return "";
    }
    return nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return "Pedido{usuario=" + usuario
           + ", operacao=" + operacao
           + ", arquivo=" + nomeArquivo
           + ", bytes=" + tamanhoBytes
           + ", origem=" + enderecoOrigem + "}";
  }
}
