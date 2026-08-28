package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector LinkedIn.
 *
 * <p>Verificacao: {@code GET <url>/v2/userinfo} com {@code Authorization:
 * Bearer}, que e' o endpoint OpenID Connect e responde 401 para token expirado.
 *
 * <p>OBSERVACAO OPERACIONAL QUE MUDA O DIA A DIA: o token do LinkedIn e' de vida
 * curta (dois meses na maioria dos aplicativos) e nao se renova sozinho sem
 * fluxo OAuth completo. Ou seja, este conector VAI passar para
 * {@code FALHOU http.401} periodicamente, e isso e' funcionamento normal, nao
 * defeito. Por ser previsivel, e' exatamente o tipo de caso em que a distincao
 * entre {@code NAO_CONFIGURADO} e {@code FALHOU} vale: quem so' tivesse booleano
 * nao conseguiria diferenciar "nunca foi configurado" de "expirou de novo".
 *
 * <p>Webhook: o LinkedIn nao assina o corpo dos eventos que envia. Usa-se token
 * compartilhado, com as limitacoes descritas em
 * {@link AssinaturaTokenCompartilhado}.
 */
public final class ConectorLinkedin extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "tokenWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do LinkedIn"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token de acesso OAuth"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Token combinado do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectLinkedin", "Vincular conta LinkedIn", Categoria.INTEGRACAO),
      new Gatilho("sharePostLinkedin", "Publicar",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("commentPostLinkedin", "Comentar publicacao", Categoria.TRABALHO_EQUIPE),
      new Gatilho("reactToPostLinkedin", "Reagir a publicacao", Categoria.TRABALHO_EQUIPE),
      new Gatilho("followPageLinkedin", "Seguir a pagina da organizacao",
          Categoria.GESTAO_COMUNIDADE));

  private final Assinatura assinatura = new AssinaturaTokenCompartilhado(
      CHAVE_SEGREDO_WEBHOOK, "X-Li-Webhook-Token",
      "o LinkedIn nao assina o corpo dos eventos");

  public ConectorLinkedin() {
    this(null);
  }

  public ConectorLinkedin(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "linkedin";
  }

  @Override
  public String nome() {
    return "LinkedIn";
  }

  @Override
  public String icone() {
    return "fab fa-linkedin";
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
    return "v2/userinfo";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
  }
}
