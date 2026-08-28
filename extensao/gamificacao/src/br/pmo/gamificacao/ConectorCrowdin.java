package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Crowdin, para gamificar traducao.
 *
 * <p>Verificacao: {@code GET <url>/api/v2/user} com {@code Authorization:
 * Bearer}.
 *
 * <p>A URL e' declarada porque o Crowdin Enterprise vive em
 * {@code https://<organizacao>.api.crowdin.com}, diferente do Crowdin publico.
 * Chumbar o endereco publico quebraria a instalacao corporativa.
 *
 * <p>Webhook: o Crowdin permite cabecalho combinado, nao HMAC sobre o corpo. Ver
 * {@link AssinaturaTokenCompartilhado}.
 */
public final class ConectorCrowdin extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "tokenWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Crowdin"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token de acesso pessoal"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Token combinado do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectCrowdin", "Vincular conta Crowdin", Categoria.INTEGRACAO),
      new Gatilho("suggestionAdded", "Sugerir uma traducao",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("suggestionApproved", "Ter traducao aprovada",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("stringCommentCreated", "Comentar um termo", Categoria.TRABALHO_EQUIPE),
      new Gatilho("translationVoted", "Votar numa traducao", Categoria.TRABALHO_EQUIPE));

  private final Assinatura assinatura = new AssinaturaTokenCompartilhado(
      CHAVE_SEGREDO_WEBHOOK, "X-Crowdin-Webhook-Token",
      "o Crowdin oferece cabecalho combinado, nao HMAC sobre o corpo");

  public ConectorCrowdin() {
    this(null);
  }

  public ConectorCrowdin(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "crowdin";
  }

  @Override
  public String nome() {
    return "Crowdin";
  }

  @Override
  public String icone() {
    return "fas fa-language";
  }

  @Override
  public List<CampoConfig> campos() {
    return CAMPOS;
  }

  @Override
  public List<Gatilho> gatilhos() {
    return GATILHOS;
  }

  @Override
  public Assinatura assinatura() {
    return assinatura;
  }

  @Override
  protected String caminhoVerificacao(Configuracao config) {
    return "api/v2/user";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
  }
}
