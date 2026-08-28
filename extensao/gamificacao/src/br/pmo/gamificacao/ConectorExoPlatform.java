package br.pmo.gamificacao;

import java.util.List;

/**
 * Conector eXo Platform: os gatilhos de colaboracao do portal.
 *
 * <p>Cobre o que as pessoas fazem no dia a dia da plataforma -- publicar,
 * comentar, curtir, subir documento, criar e moderar espaco, completar o
 * perfil. Sem servidor externo: ver {@link ConectorNativo}.
 *
 * <p>Os identificadores seguem o vocabulario que o motor de gamificacao da
 * plataforma ja' usa. Nao sao inventados aqui de proposito: um gatilho com nome
 * proprio nunca seria casado com o evento real emitido pelo portal, e a regra de
 * pontuacao criada sobre ele jamais pontuaria ninguem -- falha silenciosa, a
 * pior especie.
 */
public final class ConectorExoPlatform extends ConectorNativo {

  private static final List<Gatilho> GATILHOS = gatilhos(
      new Gatilho("loginPlatform", "Entrar na plataforma", Categoria.INTEGRACAO),
      new Gatilho("addUserProfileAvatar", "Definir foto do perfil", Categoria.INTEGRACAO),
      new Gatilho("addUserProfileBanner", "Definir capa do perfil", Categoria.INTEGRACAO),
      new Gatilho("createNewSpace", "Criar um espaco", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("joinSpace", "Entrar num espaco", Categoria.GESTAO_COMUNIDADE),
      new Gatilho("addUserToSpace", "Convidar alguem para um espaco",
          Categoria.GESTAO_COMUNIDADE),
      new Gatilho("createNewActivity", "Publicar uma atividade",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("commentActivity", "Comentar uma atividade", Categoria.TRABALHO_EQUIPE),
      new Gatilho("likeActivity", "Curtir uma atividade", Categoria.TRABALHO_EQUIPE),
      new Gatilho("addDocument", "Enviar um documento",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("createWikiPage", "Escrever pagina de wiki",
          Categoria.COMPARTILHAMENTO_CONHECIMENTO),
      new Gatilho("completeTask", "Concluir uma tarefa", Categoria.TRABALHO_EQUIPE));

  @Override
  public String id() {
    return "exo";
  }

  @Override
  public String nome() {
    return "eXo Platform";
  }

  @Override
  public String icone() {
    return "fas fa-globe";
  }

  @Override
  public List<Gatilho> gatilhos() {
    return GATILHOS;
  }
}
