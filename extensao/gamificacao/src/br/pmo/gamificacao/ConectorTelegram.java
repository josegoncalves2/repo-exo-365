package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * Conector Telegram.
 *
 * <p>Verificacao: {@code GET <url>/bot<token>/getMe}.
 *
 * <p><b>RISCO CONHECIDO E INEVITAVEL: o Telegram poe o token DENTRO DA URL.</b>
 * Nao ha' alternativa -- a API dele nao aceita o token em cabecalho. A
 * consequencia e' que o token aparece em qualquer lugar que registre URL:
 * log de proxy, log de servidor, historico de depuracao. Por isso:
 *
 * <ul>
 *   <li>{@link Resultado} desta extensao nunca inclui a URI da requisicao no
 *       detalhe -- ver o tratamento de {@code IOException} em
 *       {@link ConectorHttpBase#verificar}, que registra so' o tipo da excecao
 *       justamente para nao arrastar a URL com o token junto;
 *   <li>o token continua marcado como segredo, entao nao sai em
 *       {@link Configuracao#toString()};
 *   <li>quem operar deve evitar proxy que registre URL completa no caminho ate'
 *       {@code api.telegram.org}. Isso e' item de infraestrutura, fora do
 *       alcance deste codigo, e esta' escrito aqui para nao se perder.
 * </ul>
 *
 * <p>Resposta: o Telegram devolve {@code {"ok":true,...}}, e assim como o Slack
 * pode devolver 200 com {@code ok:false}. Por isso {@link #interpretarSucesso}
 * confere o campo.
 *
 * <p>Webhook: o Telegram nao assina o corpo; ele repete um token combinado em
 * {@code X-Telegram-Bot-Api-Secret-Token}. Ver
 * {@link AssinaturaTokenCompartilhado} para o que isso deixa de proteger.
 */
public final class ConectorTelegram extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "segredoWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Telegram"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token do bot"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Token secreto do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectTelegram", "Vincular conta Telegram", Categoria.INTEGRACAO),
      new Gatilho("sendMessageTelegram", "Enviar mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("joinGroupTelegram", "Entrar no grupo", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("inviteMemberTelegram", "Convidar um membro", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("shareContentTelegram", "Compartilhar conteudo",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO));

  private final Assinatura assinatura = new AssinaturaTokenCompartilhado(
      CHAVE_SEGREDO_WEBHOOK, "X-Telegram-Bot-Api-Secret-Token",
      "a API do Telegram nao calcula HMAC sobre o corpo");

  public ConectorTelegram() {
    this(null);
  }

  public ConectorTelegram(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "telegram";
  }

  @Override
  public String nome() {
    return "Telegram";
  }

  @Override
  public String icone() {
    return "fab fa-telegram";
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

  /** O token entra no caminho porque a API do Telegram exige. Ver a classe. */
  @Override
  protected String caminhoVerificacao(Configuracao config) {
    return "bot" + config.segredo(CHAVE_TOKEN).revelar() + "/getMe";
  }

  /** Nada a fazer: a credencial ja' foi para o caminho. */
  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Accept", "application/json");
  }

  @Override
  protected Resultado interpretarSucesso(String corpo, Configuracao config) {
    Map<String, Object> objeto;
    try {
      objeto = Json.lerObjeto(corpo);
    } catch (Json.JsonInvalidoException e) {
      return Resultado.falhou("json.malformado", "corpo nao e' JSON de objeto: " + e.getMessage());
    }
    if (!Json.booleano(objeto, "ok")) {
      return Resultado.falhou("provedor.recusou", "Telegram respondeu 200 com ok=false");
    }
    return Resultado.ok("Telegram confirmou o bot");
  }
}
