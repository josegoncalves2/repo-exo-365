package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Twitter / X.
 *
 * <p>Verificacao: {@code GET <url>/2/users/me} com {@code Authorization:
 * Bearer}.
 *
 * <p>NOTA SOBRE LIMITE DE TAXA, que aqui e' regra e nao excecao: os planos
 * gratuitos da API do Twitter tem cota baixissima, e {@code 429} e' resposta
 * comum mesmo com credencial perfeita. Por isso {@code http.429} tem codigo
 * proprio em {@link ConectorHttpBase#traduzir} e nao se confunde com
 * {@code http.401}: um pede paciencia, o outro pede token novo. Confundir os
 * dois faria o operador trocar uma credencial que estava boa.
 *
 * <p>Webhook: os eventos de Account Activity usam um desafio proprio (CRC) que
 * nao e' assinatura de corpo. Aqui se usa token compartilhado, com as ressalvas
 * de {@link AssinaturaTokenCompartilhado}.
 */
public final class ConectorTwitter extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_SEGREDO_WEBHOOK = "tokenWebhook";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Twitter/X"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Bearer token do aplicativo"),
      CampoConfig.segredoOpcional(CHAVE_SEGREDO_WEBHOOK, "Token combinado do webhook"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectTwitter", "Vincular conta Twitter", Categoria.INTEGRACAO),
      new Gatilho("tweetTwitter", "Publicar", Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("retweetTwitter", "Repostar", Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("likeTweetTwitter", "Curtir uma publicacao", Categoria.TRABALHO_EQUIPE),
      new Gatilho("mentionTwitter", "Mencionar a organizacao", Categoria.GESTAO_COMUNIDADE));

  private final Assinatura assinatura = new AssinaturaTokenCompartilhado(
      CHAVE_SEGREDO_WEBHOOK, "X-Twitter-Webhooks-Token",
      "o desafio CRC do Twitter nao assina o corpo do evento");

  public ConectorTwitter() {
    this(null);
  }

  public ConectorTwitter(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "twitter";
  }

  @Override
  public String nome() {
    return "Twitter";
  }

  @Override
  public String icone() {
    return "fab fa-twitter";
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
    return "2/users/me";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bearer " + config.segredo(CHAVE_TOKEN).revelar());
  }
}
