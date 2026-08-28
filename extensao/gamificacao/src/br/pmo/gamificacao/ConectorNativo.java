package br.pmo.gamificacao;

import java.util.Collections;
import java.util.List;

/**
 * Base dos conectores que NAO falam com servidor externo: Meeds e eXo Platform.
 *
 * <p>Os eventos deles nascem dentro do proprio portal -- alguem publicou,
 * comentou, criou espaco, entrou num espaco. Nao ha' API remota para consultar
 * nem credencial para guardar.
 *
 * <p>POR QUE ENTAO ELES SAO CONECTORES, e nao um caso a parte. Porque o painel,
 * as regras de pontuacao e o cadastro de gatilhos tratam todos igual. Se os
 * nativos ficassem fora da interface, cada tela teria de somar "os conectores,
 * mais os dois de dentro" -- e a segunda tela a ser escrita esqueceria.
 *
 * <p>CONSEQUENCIA HONESTA: {@link #estaConfigurado} devolve SEMPRE {@code true}
 * e {@link #verificar} devolve SEMPRE {@code OK}. Nao e' otimismo, e' a verdade
 * do caso: nao existe credencial que possa faltar nem provedor que possa
 * recusar. O que pode falhar num conector nativo -- o portal estar no ar -- ja'
 * esta' respondido pelo fato de este codigo estar executando.
 *
 * <p>Webhook: nao recebem. {@link #assinatura()} e' {@code null} e
 * {@link #receberWebhook} recusa com {@code webhook.nao.suportado}, em vez de
 * aceitar sem conferir. Um endereco de webhook que aceita tudo seria uma porta
 * aberta para pontuar em nome de qualquer pessoa.
 */
public abstract class ConectorNativo implements Conector {

  @Override
  public List<CampoConfig> campos() {
    return Collections.emptyList();
  }

  @Override
  public Assinatura assinatura() {
    return null;
  }

  @Override
  public boolean estaConfigurado(Configuracao config) {
    return true;
  }

  @Override
  public Resultado verificar(Configuracao config) {
    return Resultado.ok("conector nativo: eventos vem do proprio portal");
  }

  @Override
  public Resultado receberWebhook(Configuracao config, EventoEntrada evento) {
    return Resultado.falhou("webhook.nao.suportado",
        "conector nativo '" + id() + "' nao recebe webhook de fora");
  }

  protected static List<Gatilho> gatilhos(Gatilho... gatilhos) {
    return Collections.unmodifiableList(java.util.Arrays.asList(gatilhos));
  }

  @Override
  public String toString() {
    return "Conector[" + id() + "]";
  }
}
