package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Microsoft Teams, pela API Microsoft Graph.
 *
 * <p>Verificacao: {@code GET <url>/v1.0/me} com {@code Authorization: Bearer}.
 *
 * <p>Webhook: as notificacoes de mudanca do Graph nao trazem HMAC do corpo; a
 * Microsoft usa o campo {@code clientState}, um valor combinado que o assinante
 * define e que volta em cada notificacao. E' token compartilhado com outro nome,
 * e vale tudo o que esta' em {@link AssinaturaTokenCompartilhado}. O cabecalho
 * usado aqui e' o que a borda deve preencher a partir do {@code clientState}
 * recebido.
 *
 * <p>NOTA sobre 403: no Graph, {@code 403} quase sempre significa consentimento
 * de administrador nao concedido para a permissao pedida -- e nao token
 * invalido. Como {@link ConectorHttpBase#traduzir} separa {@code http.403} de
 * {@code http.401}, o painel consegue dizer "peca consentimento" em vez de
 * "troque o token", que seria conselho errado.
 */
public final class ConectorTeams extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "clientState";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API Microsoft Graph"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token de acesso do aplicativo"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "clientState combinado"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectTeams", "Vincular conta Teams", Categoria.INTEGRACAO),
      new Gatilho("sendMessageTeams", "Enviar mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("reactToMessageTeams", "Reagir a uma mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("createTeamTeams", "Criar uma equipe", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("joinTeamTeams", "Entrar numa equipe", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("shareFileTeams", "Compartilhar um arquivo",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO));

  private final Assinatura assinatura = new AssinaturaTokenCompartilhado(
      CHAVE_SEGREDO_WEBHOOK, "X-Graph-Client-State",
      "o Microsoft Graph valida por clientState, nao por HMAC do corpo");

  public ConectorTeams() {
    this(null);
  }

  public ConectorTeams(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "teams";
  }

  @Override
  public String nome() {
    return "Microsoft Teams";
  }

  @Override
  public String icone() {
    return "fab fa-microsoft";
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
    return "v1.0/me";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
  }
}
