package br.pmo.gamificacao;

import java.util.List;

/**
 * Um conector de gamificacao: identidade, configuracao DECLARADA, conferencia de
 * configuracao, e os gatilhos que sabe emitir.
 *
 * <p>REGRA DE OURO DESTA EXTENSAO, e a razao de a interface ser assim: <b>um
 * conector existe mesmo sem credencial nenhuma.</b> Instalar a extensao faz os
 * treze aparecerem no painel; cada um informa o que precisa
 * ({@link #campos()}), responde se ja' recebeu isso ({@link #estaConfigurado})
 * e, enquanto nao recebeu, devolve {@code NAO_CONFIGURADO} -- que nao e' erro,
 * e' convite. Cadastrar token e' ato posterior, feito na tela por quem tem a
 * credencial, e nao condicao para o codigo existir.
 *
 * <p>E' por isso que nenhum metodo aqui recebe token por parametro: a
 * credencial chega dentro de {@link Configuracao}, que sabe quais campos sao
 * segredo e os mascara em log.
 *
 * <p>SEM VALOR CHUMBADO. Nem URL de API, nem identificador de cliente. Tudo o
 * que aponta para fora e' campo declarado. Motivo pratico: Github, Slack e
 * Crowdin tem instalacao propria em empresa (Github Enterprise, Slack em
 * dominio proprio), e URL chumbada tornaria o conector inutil justamente na
 * instalacao corporativa que e' o caso desta plataforma.
 */
public interface Conector {

  /** Identificador estavel, minusculo, sem espaco. Vai para o banco. */
  String id();

  /** Nome legivel para a tela. */
  String nome();

  /**
   * Icone, no vocabulario de fontes de icone que o portal ja' carrega
   * (por exemplo {@code fab fa-github}).
   */
  String icone();

  /** Campos que este conector exige ou aceita. Nunca vazio para conector HTTP. */
  List<CampoConfig> campos();

  /** Gatilhos que este conector sabe emitir, com sua categoria. */
  List<Gatilho> gatilhos();

  /**
   * Esquema de conferencia de webhook, ou {@code null} para conector que nao
   * recebe webhook (os nativos, que sao alimentados por evento interno).
   */
  Assinatura assinatura();

  /**
   * Ha' valor para todos os campos obrigatorios?
   *
   * <p>Nao fala com a rede. Responde sobre o CADASTRO, nao sobre a validade da
   * credencial -- token cadastrado e revogado devolve {@code true} aqui e
   * {@code FALHOU} em {@link #verificar}. Sao perguntas diferentes e o painel
   * precisa das duas.
   */
  default boolean estaConfigurado(Configuracao config) {
    return config.completa();
  }

  /**
   * Confere a credencial CONTRA O PROVEDOR, de verdade.
   *
   * @return {@code NAO_CONFIGURADO} se falta campo obrigatorio -- e nesse caso
   *         <b>nenhuma requisicao e' feita</b>; {@code OK} se o provedor
   *         confirmou; {@code FALHOU} com codigo especifico
   *         ({@code http.401}, {@code json.malformado},
   *         {@code destino.host.divergente}, {@code rede.indisponivel}) quando
   *         havia credencial e a conversa deu errado
   */
  Resultado verificar(Configuracao config);

  /**
   * Recebe um webhook e diz se ele e' autentico.
   *
   * @return {@code NAO_CONFIGURADO} se o segredo de webhook nao foi cadastrado
   *         -- e nesse caso o evento e' RECUSADO, nunca aceito; {@code FALHOU}
   *         se a assinatura nao confere ou o corpo nao e' JSON valido;
   *         {@code OK} so' quando a autenticidade foi conferida
   */
  Resultado receberWebhook(Configuracao config, EventoEntrada evento);
}
