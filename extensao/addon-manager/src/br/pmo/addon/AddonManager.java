package br.pmo.addon;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Gerenciador de Add-ons para eXo Platform.
 *
 * <p>Gerencia ativacao, desativacao, listagem e atualizacao de extensoes
 * registradas no diretorio {@code extensao/}. Nao e' um gerenciador de
 * add-ons do eXo (que exigiria acesso ao kernel e ao AddonManager nativo):
 * e' uma ferramenta interna que opera sobre as extensoes proprias do PMO.
 *
 * <p>CADA extensao tem um manifesto ({@code .addon}) com:
 * <ul>
 *   <li>{@code id} — identificador unico (ex: "dlp-br")</li>
 *   <li>{@code nome} — nome legivel (ex: "DLP Brasil")</li>
 *   <li>{@code ativo} — true/false</li>
 *   <li>{@code versao} — versao atual</li>
 *   <li>{@code descricao} — descricao curta</li>
 * </ul>
 *
 * <p>Nunca chumba caminhos: o diretorio raiz das extensoes vem de
 * {@code System.getProperty("exo.extensoes.dir", "extensao")}.
 */
public class AddonManager {

  public static final String EXTENSOES_DIR = System.getProperty("exo.extensoes.dir", "extensao");
  public static final String MANIFEST = ".addon";

  private final Path raiz;

  public AddonManager() {
    this(Paths.get(EXTENSOES_DIR));
  }

  public AddonManager(Path raiz) {
    this.raiz = raiz.toAbsolutePath().normalize();
  }

  /** Lista todos os add-ons (pastas com manifesto). */
  public List<Addon> listar() throws IOException {
    if (!Files.isDirectory(raiz)) {
      return Collections.emptyList();
    }
    List<Addon> resultado = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(raiz)) {
      for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
        Path manifesto = dir.resolve(MANIFEST);
        if (Files.exists(manifesto)) {
          resultado.add(Addon.ler(manifesto));
        }
      }
    }
    Collections.sort(resultado);
    return resultado;
  }

  /** Ativa um add-on pelo id. */
  public Addon ativar(String id) throws IOException {
    Addon a = encontrar(id);
    if (a == null) {
      throw new IllegalArgumentException("add-on nao encontrado: " + id);
    }
    if (a.ativo) {
      return a;
    }
    a.ativo = true;
    a.escrever(raiz.resolve(id).resolve(MANIFEST));
    return a;
  }

  /** Desativa um add-on pelo id. */
  public Addon desativar(String id) throws IOException {
    Addon a = encontrar(id);
    if (a == null) {
      throw new IllegalArgumentException("add-on nao encontrado: " + id);
    }
    if (!a.ativo) {
      return a;
    }
    a.ativo = false;
    a.escrever(raiz.resolve(id).resolve(MANIFEST));
    return a;
  }

  /** Busca um add-on pelo id. */
  public Addon encontrar(String id) throws IOException {
    Path manifesto = raiz.resolve(id).resolve(MANIFEST);
    if (!Files.exists(manifesto)) {
      return null;
    }
    return Addon.ler(manifesto);
  }

  /** Modelo de um add-on. */
  public static class Addon implements Comparable<Addon> {
    public String id;
    public String nome;
    public boolean ativo;
    public String versao;
    public String descricao;

    public Addon() {}

    public Addon(String id, String nome, boolean ativo, String versao, String descricao) {
      this.id = id;
      this.nome = nome;
      this.ativo = ativo;
      this.versao = versao;
      this.descricao = descricao;
    }

    static Addon ler(Path manifesto) throws IOException {
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(manifesto)) {
        p.load(in);
      }
      Addon a = new Addon();
      a.id = p.getProperty("id", "").trim();
      a.nome = p.getProperty("nome", "").trim();
      a.ativo = Boolean.parseBoolean(p.getProperty("ativo", "false"));
      a.versao = p.getProperty("versao", "0.0.0").trim();
      a.descricao = p.getProperty("descricao", "").trim();
      return a;
    }

    void escrever(Path manifesto) throws IOException {
      Properties p = new Properties();
      p.setProperty("id", id);
      p.setProperty("nome", nome);
      p.setProperty("ativo", String.valueOf(ativo));
      p.setProperty("versao", versao);
      p.setProperty("descricao", descricao);
      try (OutputStream out = Files.newOutputStream(manifesto)) {
        p.store(out, "Add-on PMO — gerado pelo AddonManager");
      }
    }

    @Override
    public int compareTo(Addon o) {
      return this.id.compareTo(o.id);
    }

    @Override
    public String toString() {
      return id + " (" + nome + ") v" + versao + " [" + (ativo ? "ATIVO" : "INATIVO") + "]";
    }
  }
}
