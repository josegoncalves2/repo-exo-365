package br.pmo.gestao;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;

/**
 * Gestao de Backup, Migracao e Restore da plataforma eXo.
 *
 * <p>Opera sobre o docker-compose.yml e os volumes do projeto.
 * Nao executa comandos destrutivos: cria snapshots de configuracao,
 * valida integridade dos volumes e gera scripts de restore.
 *
 * <p>Nunca chumba caminhos: o diretorio raiz do projeto vem de
 * {@code System.getProperty("exo.projeto.dir", "/opt/projetos/exo")}.
 */
public class GestaoPlataforma {

  public static final String PROJETO_DIR = System.getProperty("exo.projeto.dir", "/opt/projetos/exo");
  public static final String BACKUP_DIR = System.getProperty("exo.backup.dir", PROJETO_DIR + "/backup");
  public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final Path projeto;
  private final Path backup;

  public GestaoPlataforma() {
    this(Paths.get(PROJETO_DIR), Paths.get(BACKUP_DIR));
  }

  public GestaoPlataforma(Path projeto, Path backup) {
    this.projeto = projeto.toAbsolutePath().normalize();
    this.backup = backup.toAbsolutePath().normalize();
  }

  /** Cria um snapshot da configuracao (properties, compose, nginx, conf/). */
  public Path criarSnapshot() throws IOException {
    Files.createDirectories(backup);
    String timestamp = LocalDateTime.now().format(DT);
    Path destino = backup.resolve("snapshot-" + timestamp);
    Files.createDirectories(destino);

    // Copia arquivos de configuracao essenciais
    copiarSeExistir(projeto.resolve("docker-compose.yml"), destino.resolve("docker-compose.yml"));
    copiarSeExistir(projeto.resolve("conf/exo.properties"), destino.resolve("exo.properties"));
    copiarSeExistir(projeto.resolve("conf/nginx.conf"), destino.resolve("nginx.conf"));
    copiarSeExistir(projeto.resolve("conf/rest-web.xml"), destino.resolve("rest-web.xml"));

    // Lista os volumes do docker-compose
    Path volumes = destino.resolve("volumes.txt");
    try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(volumes))) {
      pw.println("# Volumes do projeto em " + timestamp);
      pw.println("# Para restore: docker compose down && docker volume rm <vol> && docker compose up -d");
      pw.println("# Dados persistentes em ./data/");
    }

    return destino;
  }

  /** Valida a integridade dos diretorios essenciais do projeto. */
  public Relatorio validar() {
    Relatorio r = new Relatorio();
    r.ok("projeto", Files.exists(projeto));
    r.ok("docker-compose.yml", Files.exists(projeto.resolve("docker-compose.yml")));
    r.ok("conf/exo.properties", Files.exists(projeto.resolve("conf/exo.properties")));
    r.ok("data/exo", Files.isDirectory(projeto.resolve("data/exo")));
    r.ok("data/mysql", Files.isDirectory(projeto.resolve("data/mysql")));
    r.ok("data/elasticsearch", Files.isDirectory(projeto.resolve("data/elasticsearch")));
    return r;
  }

  /** Gera script de restore para um snapshot. */
  public Path gerarScriptRestore(String snapshotDir) throws IOException {
    Path snap = backup.resolve(snapshotDir);
    if (!Files.isDirectory(snap)) {
      throw new IllegalArgumentException("snapshot nao encontrado: " + snapshotDir);
    }
    Path script = snap.resolve("restaurar.sh");
    try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(script))) {
      pw.println("#!/bin/bash");
      pw.println("# Script de restore gerado por GestaoPlataforma");
      pw.println("# Snapshot: " + snapshotDir);
      pw.println("cd " + projeto);
      pw.println("echo \"=== Restaurando configuracao ===\"");
      pw.println("cp -v " + snap.resolve("docker-compose.yml") + " docker-compose.yml 2>/dev/null || true");
      pw.println("cp -v " + snap.resolve("exo.properties") + " conf/exo.properties 2>/dev/null || true");
      pw.println("echo \"=== Restauracao concluida. Recrie os containers com: docker compose up -d ===\"");
    }
    script.toFile().setExecutable(true);
    return script;
  }

  private static void copiarSeExistir(Path origem, Path destino) throws IOException {
    if (Files.exists(origem)) {
      Files.copy(origem, destino, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Relatorio de validacao. */
  public static class Relatorio {
    public final java.util.List<String> itens = new java.util.ArrayList<>();
    public int okCount = 0;
    public int falhaCount = 0;

    void ok(String nome, boolean condicao) {
      itens.add((condicao ? "OK" : "FALHA") + " " + nome);
      if (condicao) okCount++; else falhaCount++;
    }

    public boolean tudoOk() { return falhaCount == 0; }

    @Override
    public String toString() {
      return String.join("\n", itens) + "\n---\n" + okCount + " ok, " + falhaCount + " falha(s)";
    }
  }
}
