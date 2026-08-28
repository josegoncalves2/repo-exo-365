package br.pmo.gamificacao;

/**
 * Conferencia por TOKEN COMPARTILHADO em cabecalho, para provedores que nao
 * assinam o corpo.
 *
 * <p><b>ESTE ESQUEMA E' MAIS FRACO QUE HMAC, E O RISCO ESTA' DOCUMENTADO AQUI
 * PORQUE O OPERADOR PRECISA SABER DISSO.</b> Provedores como Telegram, Crowdin,
 * LinkedIn, Teams e Snapshot nao calculam assinatura sobre o conteudo enviado;
 * o que oferecem e', no maximo, repetir um token combinado num cabecalho. As
 * consequencias, ditas sem eufemismo:
 *
 * <ul>
 *   <li><b>O corpo nao e' protegido.</b> HMAC garante que nem um byte do
 *       conteudo mudou. Um token repetido nao garante nada sobre o conteudo:
 *       quem conseguir se colocar no meio do caminho pode alterar o evento e
 *       manter o token, e a conferencia continua passando.
 *   <li><b>O token e' reutilizavel.</b> Ele viaja igual em toda requisicao.
 *       Quem o ler uma vez -- num log de proxy, num despejo de trafego -- pode
 *       forjar eventos indefinidamente. Um HMAC lido de uma requisicao nao
 *       serve para a proxima, porque muda com o corpo.
 *   <li><b>Nao ha' defesa contra repeticao.</b> Sem carimbo assinado, a mesma
 *       requisicao reenviada e' indistinguivel da original.
 * </ul>
 *
 * <p>O que se pode fazer, e esta' feito: comparar em tempo constante (senao o
 * token vaza por temporizacao), exigir que ele exista (sem token cadastrado a
 * resposta e' NAO_CONFIGURADO, nunca OK) e manter HTTPS ate' a borda -- este
 * ultimo fora do alcance deste codigo, e por isso registrado aqui como
 * pendencia de infraestrutura.
 *
 * <p>Onde o provedor oferecer HMAC, use {@link AssinaturaHmacHex}. Este esquema
 * e' o piso, nao o padrao.
 */
public final class AssinaturaTokenCompartilhado implements Assinatura {

  private final String chaveSegredo;

  private final String cabecalho;

  private final String provedorNaoAssina;

  /**
   * @param chaveSegredo chave de configuracao com o token combinado
   * @param cabecalho cabecalho onde o provedor repete o token
   * @param provedorNaoAssina explicacao curta do porque nao ha' HMAC aqui
   */
  public AssinaturaTokenCompartilhado(String chaveSegredo, String cabecalho,
      String provedorNaoAssina) {
    this.chaveSegredo = chaveSegredo;
    this.cabecalho = cabecalho;
    this.provedorNaoAssina = provedorNaoAssina;
  }

  @Override
  public String chaveSegredo() {
    return chaveSegredo;
  }

  @Override
  public String descricao() {
    return "token compartilhado em " + cabecalho + " (SEM HMAC: " + provedorNaoAssina + ")";
  }

  @Override
  public Resultado conferir(Configuracao config, EventoEntrada evento) {
    Segredo esperado = config.segredo(chaveSegredo);
    if (esperado.vazio()) {
      return Resultado.naoConfigurado("sem token de webhook em '" + chaveSegredo
          + "': webhook nao pode ser conferido");
    }
    String recebido = evento.cabecalho(cabecalho);
    if (recebido == null || recebido.isBlank()) {
      return Resultado.falhou("assinatura.ausente", "cabecalho " + cabecalho + " nao veio");
    }
    if (!Bytes.iguaisTempoConstante(
        recebido.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        esperado.revelar().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      return Resultado.falhou("assinatura.invalida", "token de webhook nao confere");
    }
    return Resultado.ok("token de webhook conferido (esquema sem HMAC)");
  }
}
