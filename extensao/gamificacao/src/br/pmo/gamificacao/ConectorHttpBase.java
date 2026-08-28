package br.pmo.gamificacao;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base dos conectores que falam HTTP com um provedor externo.
 *
 * <p>Concentra o que os onze conectores de rede fazem igual: conferir se ha'
 * configuracao ANTES de tocar na rede, montar a requisicao amarrada ao host
 * configurado, traduzir status HTTP em {@link Resultado} com codigo especifico,
 * e conferir a assinatura do webhook. O que muda de provedor para provedor --
 * o caminho de verificacao e o formato do cabecalho de autorizacao -- fica nos
 * dois metodos abstratos.
 *
 * <p>POR QUE A TRADUCAO DE STATUS E' AQUI E NAO EM CADA CONECTOR. Se cada um
 * traduzisse, um deles acabaria tratando 401 como "indisponivel" e o operador
 * veria "provedor fora do ar" onde a verdade e' "seu token foi revogado" -- duas
 * causas com acoes completamente diferentes. Traduzido num lugar so', o
 * vocabulario de codigos e' o mesmo para os treze.
 */
public abstract class ConectorHttpBase implements Conector {

  /** Chave do campo de URL base. Declarada, nunca chumbada. */
  public static final String CHAVE_URL = "url";

  private final ClienteHttp cliente;

  protected ConectorHttpBase(ClienteHttp cliente) {
    this.cliente = cliente == null ? new ClienteHttp() : cliente;
  }

  /** Cliente HTTP deste conector, para quem precisa montar requisicao propria. */
  protected final ClienteHttp cliente() {
    return cliente;
  }

  /**
   * Monta a requisicao de verificacao. Por padrao GET autenticado.
   *
   * <p>Existe como ponto de sobrescrita porque nem todo provedor se verifica com
   * GET: um no de blockchain EVM so' responde a JSON-RPC por POST. Sem este
   * gancho, o conector EVM teria de burlar a base e perderia junto as defesas de
   * host que ela aplica.
   */
  protected HttpRequest montarRequisicao(URI destino, Configuracao config) {
    HttpRequest.Builder b = cliente.get(destino);
    autenticar(b, config);
    return b.build();
  }

  /**
   * Caminho, relativo a' URL base, que confirma a credencial.
   *
   * <p>Recebe a configuracao porque ha' provedor cujo caminho DEPENDE dela: o
   * Telegram poe o token do bot dentro da URL ({@code /bot<token>/getMe}) e o
   * Snapshot poe o identificador do espaco. Um caminho constante nao daria conta
   * desses dois sem chumbar valor, que e' proibido aqui.
   */
  protected abstract String caminhoVerificacao(Configuracao config);

  /**
   * Poe a credencial na requisicao.
   *
   * <p>E' o unico ponto de cada conector onde {@link Segredo#revelar()} e'
   * chamado. So' e' invocado depois de {@link #estaConfigurado} ter dado
   * {@code true}, entao o segredo esta' presente.
   */
  protected abstract void autenticar(HttpRequest.Builder requisicao, Configuracao config);

  /**
   * Interpreta o corpo de uma resposta bem-sucedida.
   *
   * <p>Recebe a configuracao porque ha' conector que precisa CONFERIR a resposta
   * contra o que foi cadastrado -- o EVM compara o identificador de cadeia que o
   * no' respondeu com o que o operador declarou, e sem isso apontar para a rede
   * errada passaria como sucesso.
   *
   * <p>Por padrao exige que seja um objeto JSON -- ver {@link Json} sobre por
   * que resposta que nao e' JSON valido tem de virar falha e nao sucesso
   * silencioso. Conector cujo provedor responde outra coisa sobrescreve.
   */
  protected Resultado interpretarSucesso(String corpo, Configuracao config) {
    try {
      Map<String, Object> objeto = Json.lerObjeto(corpo);
      return Resultado.ok("provedor confirmou (" + objeto.size() + " campos)");
    } catch (Json.JsonInvalidoException e) {
      return Resultado.falhou("json.malformado",
          "status de sucesso mas corpo nao e' JSON de objeto: " + e.getMessage());
    }
  }

  @Override
  public Resultado verificar(Configuracao config) {
    if (!estaConfigurado(config)) {
      // NENHUMA requisicao sai daqui. Conector recem-instalado nao gera trafego
      // nem log de erro; ele so' informa o que falta.
      return Resultado.naoConfigurado("faltam campos: " + config.faltando());
    }
    String base = config.valor(CHAVE_URL);
    try {
      URI destino = ClienteHttp.resolver(base, caminhoVerificacao(config));
      RespostaHttp resposta = cliente.enviar(base, montarRequisicao(destino, config));
      return traduzir(resposta, config);
    } catch (DestinoInvalidoException e) {
      return Resultado.falhou(e.codigo(), e.getMessage());
    } catch (IOException e) {
      // Nao inclui a mensagem crua: em alguns JDK ela traz a URI completa, e
      // certos provedores aceitam credencial em parametro de consulta.
      return Resultado.falhou("rede.indisponivel",
          "falha de rede: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      // Repor a marca de interrupcao: engoli-la faz o desligamento do portal
      // travar esperando uma linha de execucao que ja' deveria ter parado.
      Thread.currentThread().interrupt();
      return Resultado.falhou("interrompido", "verificacao interrompida");
    }
  }

  /** Traduz status HTTP no vocabulario fechado de {@link Resultado}. */
  protected Resultado traduzir(RespostaHttp resposta, Configuracao config) {
    int status = resposta.status();
    if (resposta.sucesso()) {
      return interpretarSucesso(resposta.corpo(), config);
    }
    switch (status) {
      case 401:
        return Resultado.falhou("http.401",
            "credencial recusada pelo provedor (token invalido, expirado ou revogado)");
      case 403:
        return Resultado.falhou("http.403",
            "credencial valida mas sem permissao para este recurso");
      case 404:
        return Resultado.falhou("http.404",
            "recurso nao existe: confira a URL base e o identificador");
      case 429:
        return Resultado.falhou("http.429", "provedor limitou a taxa de requisicoes");
      default:
        break;
    }
    if (status >= 500) {
      return Resultado.falhou("http.5xx", "provedor com erro interno (status " + status + ")");
    }
    return Resultado.falhou("http." + status, "status inesperado " + status);
  }

  @Override
  public Resultado receberWebhook(Configuracao config, EventoEntrada evento) {
    Assinatura assinatura = assinatura();
    if (assinatura == null) {
      return Resultado.falhou("webhook.nao.suportado",
          "conector '" + id() + "' nao recebe webhook");
    }
    Resultado conferencia = assinatura.conferir(config, evento);
    if (!conferencia.ok()) {
      // Devolve o resultado da assinatura como esta': ele ja' distingue
      // NAO_CONFIGURADO de FALHOU, e essa distincao nao pode se perder aqui.
      return conferencia;
    }
    // So' DEPOIS de autenticado o corpo e' interpretado. Interpretar antes seria
    // processar entrada de origem desconhecida -- que e' como se transforma um
    // analisador em superficie de ataque.
    try {
      Json.lerObjeto(evento.corpoTexto());
    } catch (Json.JsonInvalidoException e) {
      return Resultado.falhou("json.malformado",
          "webhook autenticado mas com corpo invalido: " + e.getMessage());
    }
    return Resultado.ok("webhook autentico");
  }

  /** Lista imutavel, para o chamador nao alterar a declaracao do conector. */
  protected static List<CampoConfig> campos(CampoConfig... campos) {
    return Collections.unmodifiableList(java.util.Arrays.asList(campos));
  }

  protected static List<Gatilho> gatilhos(Gatilho... gatilhos) {
    return Collections.unmodifiableList(java.util.Arrays.asList(gatilhos));
  }

  @Override
  public String toString() {
    return "Conector[" + id() + "]";
  }
}
