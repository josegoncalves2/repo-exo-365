package br.pmo.gamificacao;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * Conector Discord.
 *
 * <p>Verificacao: {@code GET <url>/users/@me} com {@code Authorization: Bot
 * <token>}. O prefixo {@code Bot} nao e' enfeite: sem ele o Discord responde 401
 * mesmo com token correto, porque trata a credencial como token de usuario.
 *
 * <p>Webhook: o Discord assina com <b>Ed25519</b>, assinatura assimetrica. Aqui
 * so' existe a chave publica dele, que confere e nao forja. Ver
 * {@link AssinaturaEd25519} sobre por que isso e' melhor do que HMAC neste caso.
 * A chave publica e' campo COMUM, nao segredo: ela e' publica de verdade.
 */
public final class ConectorDiscord extends ConectorHttpBase {

  public static final String CHAVE_TOKEN = "token";

  public static final String CHAVE_CHAVE_PUBLICA = "chavePublica";

  private static final List<CampoConfig> CAMPOS = campos(
      CampoConfig.obrigatorio(CHAVE_URL, "URL da API do Discord"),
      CampoConfig.segredoObrigatorio(CHAVE_TOKEN, "Token do bot"),
      CampoConfig.opcional(CHAVE_CHAVE_PUBLICA, "Chave publica da aplicacao (hexadecimal)"));

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectDiscord", "Vincular conta Discord", Categoria.INTEGRACAO),
      new Gatilho("sendMessageDiscord", "Enviar mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("reactToMessageDiscord", "Reagir a uma mensagem", Categoria.TRABALHO_EQUIPE),
      new Gatilho("joinServerDiscord", "Entrar no servidor", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("inviteMemberDiscord", "Convidar um membro", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("createThreadDiscord", "Abrir um topico",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO));

  private final Assinatura assinatura = new AssinaturaEd25519(CHAVE_CHAVE_PUBLICA);

  public ConectorDiscord() {
    this(null);
  }

  public ConectorDiscord(ClienteHttp cliente) {
    super(cliente);
  }

  @Override
  public String id() {
    return "discord";
  }

  @Override
  public String nome() {
    return "Discord";
  }

  @Override
  public String icone() {
    return "fab fa-discord";
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
    return "users/@me";
  }

  @Override
  protected void autenticar(HttpRequest.Builder requisicao, Configuracao config) {
    requisicao.header("Authorization", "Bot " + config.segredo(CHAVE_TOKEN).revelar());
  }
}
