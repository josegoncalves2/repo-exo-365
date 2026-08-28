package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Github.
 *
 * <p>Verificacao: {@code GET <url>/user} com {@code Authorization: Bearer}. E' o
 * endpoint mais barato que exige credencial valida -- responde 401 para token
 * revogado e 200 com o perfil para token bom.
 *
 * <p>A URL e' campo declarado e nao constante porque Github Enterprise Server
 * roda em dominio da propria organizacao, com API em
 * {@code https://github.<empresa>/api/v3}. Chumbar {@code api.github.com}
 * tornaria o conector inutil exatamente na instalacao corporativa.
 *
 * <p>Webhook: Github assina o corpo com HMAC-SHA256 e manda em
 * {@code X-Hub-Signature-256} com prefixo {@code sha256=}. Ver
 * {@link AssinaturaHmacHex}, inclusive sobre a limitacao de repeticao.
 */
public final class ConectorGithub extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "segredoWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API (api.github.com ou Enterprise)"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token de acesso pessoal"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Segredo do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectGithub", "Vincular conta Github", Categoria.INTEGRACAO),
      new Gatilho("pushCode", "Enviar codigo", Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("creatPullRequest", "Abrir pull request", Categoria.TRABALHO_EQUIPE),
      new Gatilho("reviewPullRequest", "Revisar pull request", Categoria.TRABALHO_EQUIPE),
      new Gatilho("commentPullRequest", "Comentar pull request", Categoria.TRABALHO_EQUIPE),
      new Gatilho("validatePullRequest", "Aprovar pull request", Categoria.TRABALHO_EQUIPE),
      new Gatilho("createIssue", "Abrir uma issue", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("commentIssue", "Comentar uma issue", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("addIssueLabel", "Etiquetar uma issue", Categoria.GESTAO_COMUNIDADE));

  private final Assinatura assinatura =
      new AssinaturaHmacHex(CHAVE_SEGREDO_WEBHOOK, "X-Hub-Signature-256", "sha256=");

  public ConectorGithub() {
    this(null);
  }

  public ConectorGithub(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "github";
  }

  @Override
  public String nome() {
    return "Github";
  }

  @Override
  public String icone() {
    return "fab fa-github";
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
    return "user";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
    requisicao.header("Accept", "application/vnd.github+json");
  }
}
