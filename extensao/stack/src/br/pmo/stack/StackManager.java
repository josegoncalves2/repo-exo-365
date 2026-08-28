package br.pmo.stack;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Gestao da Stack completa do eXo Community.
 *
 * <p>Fornece utilitarios para monitorar, diagnosticar e operar a stack
 * Docker Compose (13 containers). Nao executa comandos destrutivos:
 * apenas leitura e geracao de relatorios.
 *
 * <p>Nunca chumba caminhos: o diretorio raiz do projeto vem de
 * {@code System.getProperty("exo.projeto.dir", "/opt/projetos/exo")}.
 */
public class StackManager {

  public static final String PROJETO_DIR = System.getProperty("exo.projeto.dir", "/opt/projetos/exo");

  private final Path projeto;

  public StackManager() {
    this(Paths.get(PROJETO_DIR));
  }

  public StackManager(Path projeto) {
    this.projeto = projeto.toAbsolutePath().normalize();
  }

  /** Lista os servicos definidos no docker-compose.yml. */
  public List<String> servicos() throws IOException {
    Path compose = projeto.resolve("docker-compose.yml");
    if (!Files.exists(compose)) {
      return Collections.emptyList();
    }
    List<String> servicos = new ArrayList<>();
    for (String linha : Files.readAllLines(compose)) {
      String t = linha.trim();
      if (t.endsWith(":") && !t.startsWith("#") && !t.startsWith("-") && !t.startsWith(" ")) {
        String nome = t.substring(0, t.length() - 1).trim();
        if (!nome.isEmpty() && Character.isLetter(nome.charAt(0))) {
          servicos.add(nome);
        }
      }
    }
    return servicos;
  }

  /** Gera relatorio de dependencias entre servicos. */
  public RelatorioDependencias analisarDependencias() throws IOException {
    RelatorioDependencias r = new RelatorioDependencias();
    Path compose = projeto.resolve("docker-compose.yml");
    if (!Files.exists(compose)) {
      return r;
    }
    String conteudo = new String(Files.readAllBytes(compose));
    for (String s : servicos()) {
      int idx = conteudo.indexOf(s + ":");
      if (idx < 0) continue;
      int fim = conteudo.indexOf("\n  ", idx + s.length() + 2);
      if (fim < 0) fim = conteudo.length();
      String bloco = conteudo.substring(idx, fim);
      if (bloco.contains("depends_on")) {
        r.adicionar(s, "depende de outros servicos");
      }
      if (bloco.contains("image:")) {
        r.adicionar(s, "imagem definida");
      }
      if (bloco.contains("ports:")) {
        r.adicionar(s, "portas expostas");
      }
      if (bloco.contains("volumes:") || bloco.contains("bind:")) {
        r.adicionar(s, "volumes montados");
      }
    }
    return r;
  }

  /** Relatorio de dependencias. */
  public static class RelatorioDependencias {
    public final Map<String, List<String>> servicos = new LinkedHashMap<>();

    void adicionar(String servico, String info) {
      servicos.computeIfAbsent(servico, k -> new ArrayList<>()).add(info);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, List<String>> e : servicos.entrySet()) {
        sb.append(e.getKey()).append(":\n");
        for (String i : e.getValue()) {
          sb.append("  - ").append(i).append("\n");
        }
      }
      return sb.toString();
    }
  }
}
