package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * Conector Slack.
 *
 * <p>Verificacao: {@code GET <url>/api/auth.test} com {@code Authorization:
 * Bearer}.
 *
 * <p><b>ARMADILHA QUE ESTE CONECTOR TRATA E QUE QUASE TODO CODIGO ERRA.</b> A
 * API do Slack responde <b>HTTP 200 mesmo quando o token e' invalido</b>. O erro
 * vem no corpo, em {@code {"ok":false,"error":"invalid_auth"}}. Quem classifica
 * pelo status -- que e' o comportamento natural e o que a classe base faz por
 * padrao -- concluiria "credencial valida" para um token revogado, e o painel
 * mostraria verde para um conector que nao funciona. Por isso
 * {@link #interpretarSucesso} e' sobrescrito aqui para exigir {@code ok:true}, e
 * ha' prova dedicada a esse caso.
 *
 * <p>Webhook: Slack assina com o esquema {@code v0}, que inclui carimbo de hora
 * na mensagem assinada e portanto resiste a repeticao. Ver
 * {@link AssinaturaSlack}.
 */
public final class ConectorSlack extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "segredoAssinatura";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Slack"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token do aplicativo (bot token)"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Signing secret"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectSlack", "Vincular conta Slack", Categoria.INTEGRACAO),
      new Gatilho("sendMessageSlack", "Enviar mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("reactToMessageSlack", "Reagir a uma mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("createChannelSlack", "Criar um canal", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("inviteToChannelSlack", "Convidar para um canal",
          Categoria.GESTAO_COMUNIDADE),
      new Gatilho("shareFileSlack", "Compartilhar um arquivo",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO));

  private final Assinatura assinatura;

  public ConectorSlack() {
    this(null, new AssinaturaSlack(CHAVE_SEGREDO_WEBHOOK));
  }

  public ConectorSlack(ClienteHttp cliente) {
    this(cliente, new AssinaturaSlack(CHAVE_SEGREDO_WEBHOOK));
  }

  /** Construtor com assinatura injetada, para a prova controlar o relogio. */
  public ConectorSlack(ClienteHttp cliente, Assinatura assinatura) {
    super(cliente);
    this.assinatura = assinatura;
  }

  @Override
  public String id() {
    return "slack";
  }

  @Override
  public String nome() {
    return "Slack";
  }

  @Override
  public String icone() {
    return "fab fa-slack";
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
    return "api/auth.test";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
  }

  /**
   * Exige {@code ok:true} no corpo, porque status 200 do Slack nao significa
   * credencial valida. Ver o javadoc da classe.
   */
  @Override
  protected Resultado interpretarSucesso(String corpo, Configuracao config) {
    Map<String, Object> objeto;
    try {
      objeto = Json.lerObjeto(corpo);
    } catch (Json.JsonInvalidoException e) {
      return Resultado.falhou("json.malformado", "corpo nao e' JSON de objeto: " + e.getMessage());
    }
    if (!Json.booleano(objeto, "ok")) {
      String erro = Json.texto(objeto, "error");
      return Resultado.falhou("provedor.recusou",
          "Slack respondeu 200 com ok=false" + (erro == null ? "" : " (" + erro + ")"));
    }
    String equipe = Json.texto(objeto, "team");
    return Resultado.ok("Slack confirmou" + (equipe == null ? "" : " para a equipe " + equipe));
  }
}
