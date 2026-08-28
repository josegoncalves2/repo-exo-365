package br.pmo.gamificacao;

import java.util.List;

/**
 * Conector Meeds: os gatilhos do proprio motor de gamificacao.
 *
 * <p>E' o conector "de dentro". Os eventos nascem do uso normal do portal --
 * alguem ganhou pontos, subiu de nivel, recebeu um kudos -- e nao ha' servidor
 * externo envolvido. Ver {@link ConectorNativo} sobre por que mesmo assim ele
 * implementa {@link Conector}.
 */
public final class ConectorMeeds extends ConectorNativo {

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("connectorConnectMeeds", "Vincular conta Meeds", Categoria.INTEGRACAO),
      new Gatilho("receiveKudos", "Receber um kudos", Categoria.TRABALHO_EQUIPE),
      new Gatilho("sendKudos", "Enviar um kudos", Categoria.TRABALHO_EQUIPE),
      new Gatilho("createNewChallenge", "Criar um desafio", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("announceChallenge", "Anunciar participacao em desafio",
          Categoria.GESTAO_COMUNIDADE),
      new Gatilho("receiveReward", "Receber recompensa", Categoria.INTEGRACAO));

  @Override
  public String id() {
    return "meeds";
  }

  @Override
  public String nome() {
    return "Meeds";
  }

  @Override
  public String icone() {
    return "fas fa-award";
  }

  @Override
  public List<Gatilho> gatilhos() {
    return GATILHOS;
  }
}
