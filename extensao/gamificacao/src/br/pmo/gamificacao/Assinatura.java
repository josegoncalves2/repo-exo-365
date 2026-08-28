package br.pmo.gamificacao;

/**
 * Conferencia de autenticidade de um webhook RECEBIDO.
 *
 * <p>POR QUE ISTO NAO E' OPCIONAL. O endereco de webhook e' publico por
 * necessidade -- o provedor precisa alcanca-lo pela internet. Sem conferencia de
 * assinatura, qualquer pessoa que descubra a URL manda um POST dizendo "o
 * usuario fulano fechou trinta pull requests" e ganha os pontos. Nao ha' senha
 * envolvida, nao ha' o que auditar depois: o evento forjado e' indistinguivel do
 * legitimo. Gamificacao sem assinatura de webhook e' um placar que qualquer um
 * edita.
 *
 * <p>TRES DESFECHOS, pelo mesmo motivo de {@link Resultado}: sem segredo
 * cadastrado o webhook e' NAO_CONFIGURADO (ninguem terminou a instalacao);
 * assinatura que nao confere e' FALHOU (alguem mandou coisa que nao devia, ou o
 * segredo foi trocado de um lado so'). Nunca OK.
 *
 * <p><b>Regra que nao se quebra:</b> uma implementacao NUNCA devolve OK quando
 * nao ha' segredo cadastrado. "Sem segredo, entao aceita tudo" e' o modo de
 * falhar que transforma a defesa em enfeite.
 */
public interface Assinatura {

  /** Chave de configuracao onde mora o segredo desta conferencia. */
  String chaveSegredo();

  /** Descricao curta do esquema, para o painel e o log. */
  String descricao();

  /**
   * Confere o evento.
   *
   * @return {@link Resultado#ok} so' quando a assinatura confere de fato
   */
  Resultado conferir(Configuracao config, EventoEntrada evento);
}
