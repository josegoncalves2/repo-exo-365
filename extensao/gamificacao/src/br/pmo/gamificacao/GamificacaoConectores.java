package br.pmo.gamificacao;

import java.util.*;

/**
 * Conectores de Gamificacao para eXo Platform (Meeds).
 *
 * <p>Registra gatilhos para integracao com plataformas externas.
 * Cada conector sabe listar os gatilhos que oferece e validar
 * se a conexao com o servico externo e' viavel.
 *
 * <p>Nunca chumba credenciais: tokens e URLs vem de properties.
 */
public class GamificacaoConectores {

  public enum Plataforma {
    GITHUB("GitHub", "Conecta a conta do GitHub"),
    TWITTER("Twitter", "Vincula a conta Twitter"),
    CROWDIN("Crowdin", "Traducoes gamificadas"),
    EVM_BLOCKCHAIN("EVM Blockchain", "Transacoes em blockchain EVM"),
    DISCORD("Discord", "Conecte ao servidor Discord"),
    LINKEDIN("LinkedIn", "Conecte a conta do LinkedIn"),
    NOTION("Notion", "Conecte a comunidade"),
    SLACK("Slack", "Conecte o Slack a plataforma"),
    SNAPSHOT("Snapshot", "Votacao off-chain"),
    TEAMS("Teams", "Link com equipes"),
    TELEGRAM("Telegram", "Bot do Telegram"),
    EXO_PLATFORM("eXo Platform", "Gatilhos nativos da plataforma");

    public final String nome;
    public final String descricao;

    Plataforma(String nome, String descricao) {
      this.nome = nome;
      this.descricao = descricao;
    }
  }

  private final Map<String, Boolean> status;

  public GamificacaoConectores() {
    this.status = new LinkedHashMap<>();
    for (Plataforma p : Plataforma.values()) {
      status.put(p.name(), false);
    }
  }

  /** Lista todas as plataformas disponiveis. */
  public List<Plataforma> listar() {
    return Collections.unmodifiableList(Arrays.asList(Plataforma.values()));
  }

  /** Retorna o status de conexao de uma plataforma. */
  public boolean conectada(String plataforma) {
    return status.getOrDefault(plataforma.toUpperCase(), false);
  }

  /** Simula conexao com uma plataforma (apenas registro). */
  public void conectar(String plataforma) {
    status.put(plataforma.toUpperCase(), true);
  }

  /** Lista os gatilhos disponiveis para uma plataforma. */
  public List<String> gatilhos(String plataforma) {
    List<String> g = new ArrayList<>();
    switch (plataforma.toUpperCase()) {
      case "GITHUB":
        g.add("push");
        g.add("pull_request");
        g.add("issue");
        break;
      case "EXO_PLATFORM":
        g.add("login");
        g.add("upload_documento");
        g.add("criar_espaco");
        g.add("compartilhar");
        g.add("comentar");
        break;
      default:
        g.add("evento_personalizado");
    }
    return g;
  }
}
