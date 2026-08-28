package br.pmo.gamificacao;

/**
 * As quatro familias de gatilho que o prompt7 exige que todo conector saiba
 * classificar.
 *
 * <p>POR QUE EXISTE COMO ENUM, e nao como texto livre: o painel agrupa gatilho
 * por familia para montar as regras de pontuacao. Com texto livre, "trabalho em
 * equipe" e "Trabalho em Equipe" viram dois grupos na tela e a regra de
 * pontuacao se aplica a metade dos eventos sem ninguem perceber. Enum fecha a
 * porta em tempo de compilacao.
 */
public enum Categoria {

  /** Ligar a conta externa ao perfil do portal. */
  INTEGRACAO("Integracao"),

  /** Moderar, convidar, administrar a comunidade. */
  GESTAO_COMUNIDADE("Gestao da comunidade"),

  /** Colaborar em tarefa, revisao, entrega conjunta. */
  TRABALHO_EQUIPE("Trabalho em equipe"),

  /** Publicar, traduzir, documentar, ensinar. */
  COMPARTILHAMENTO_CONHECIMENTO("Compartilhamento de conhecimento");

  private final String rotulo;

  Categoria(String rotulo) {
    this.rotulo = rotulo;
  }

  /** Rotulo legivel, para a tela. Sem acento: o portal serve em varios charsets. */
  public String rotulo() {
    return rotulo;
  }
}
