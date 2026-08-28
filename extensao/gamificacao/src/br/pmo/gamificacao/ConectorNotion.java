package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Notion.
 *
 * <p>Verificacao: {@code GET <url>/v1/users/me} com {@code Authorization:
 * Bearer} e o cabecalho {@code Notion-Version}.
 *
 * <p>POR QUE A VERSAO DA API E' CAMPO DECLARADO E OBRIGATORIO. O Notion recusa
 * requisicao sem {@code Notion-Version} e muda de comportamento entre versoes.
 * Chumbar uma data no codigo faria o conector quebrar sozinho no dia em que
 * aquela versao sair de suporte, e a correcao exigiria recompilar e reimplantar.
 * Declarado, o operador troca a data no painel.
 *
 * <p>Webhook: o Notion assina com HMAC-SHA256 em {@code X-Notion-Signature},
 * prefixo {@code sha256=}. Ver {@link AssinaturaHmacHex}.
 */
public final class ConectorNotion extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_VERSAO = "versaoApi";

  public static final String CHAVE_SEGREDO_WEBHOOK = "segredoWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Notion"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token de integracao interna"),
      CampoConfig.obrigatorio(CHAVE_VERSAO, "Versao da API (cabecalho Notion-Version)"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Segredo de verificacao do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectNotion", "Vincular conta Notion", Categoria.INTEGRACAO),
      new Gatilho("createPageNotion", "Criar uma pagina",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("editPageNotion", "Editar uma pagina",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("commentPageNotion", "Comentar uma pagina", Categoria.TRABALHO_EQUIPE),
      new Gatilho("shareDatabaseNotion", "Compartilhar uma base",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO));

  private final Assinatura assinatura =
      new AssinaturaHmacHex(CHAVE_SEGREDO_WEBHOOK, "X-Notion-Signature", "sha256=");

  public ConectorNotion() {
    this(null);
  }

  public ConectorNotion(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "notion";
  }

  @Override
  public String nome() {
    return "Notion";
  }

  @Override
  public String icone() {
    return "fas fa-book";
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
    return "v1/users/me";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
    requisicao.header("Notion-Version", config.valor(CHAVE_VERSAO));
  }
}
