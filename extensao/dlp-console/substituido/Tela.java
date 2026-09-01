package br.pmo.dlpconsole;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O que a montagem da tela precisa saber sobre a requisicao atual.
 *
 * <p>Existe para que {@link Pagina} seja uma FUNCAO PURA: recebe este objeto e
 * os dados ja' lidos da API, devolve HTML. Nada aqui depende do contentor de
 * portlets, entao a tela inteira se prova no host, com {@code javac} e
 * {@code java}, antes de o WAR existir -- que e' a mesma escolha feita no
 * painel de conformidade deste projeto, e pela mesma razao: e' na montagem do
 * HTML que mora o escape, e escape so' testado com o portal de pe' e' escape
 * nao testado.
 */
public final class Tela {

  /** Prefixo dos identificadores, para dois portlets na mesma pagina. */
  public String espacoNomes = "";

  /** URL do formulario (ActionURL do portlet). */
  public String urlAcao = "";

  /** URL de recurso (ResourceURL) base, quando nao ha' identificador. */
  public String urlRecurso = "";

  /**
   * MODELOS de URL com o literal {@code __ID__} no lugar do identificador.
   *
   * <p>POR QUE MODELO E NAO CONCATENACAO. Num portlet, parametro cru
   * acrescentado a mao no fim de uma URL NAO chega ao
   * {@code RenderRequest.getParameter}: o contentor so' entrega o que foi
   * codificado por {@code PortletURL.setParameter}. A primeira versao desta
   * tela montava o link de detalhe como {@code url("incidentes") + "&detalhe="
   * + id} -- e o detalhe simplesmente nao abria. O portlet, que e' quem sabe
   * construir uma PortletURL, monta o modelo UMA vez com o marcador, e aqui
   * so' se troca o marcador pelo identificador.
   */
  public String urlDetalhe = "";

  /** Modelo do download do conteudo retido, com {@code __ID__}. */
  public String urlQuarentenaConteudo = "";

  /** Download do CSV de incidentes, ja' com os filtros correntes. */
  public String urlCsv = "";

  /** aba -> RenderURL. Montadas pelo portlet, que e' quem sabe faze-lo. */
  public Map<String, String> urlsAbas = new LinkedHashMap<>();

  public String aba = "painel";

  /** Retorno da ultima acao, mostrado no topo. */
  public String mensagem = "";

  public String erro = "";

  /** Quem esta' vendo. Vai como `autor` em toda acao que muda estado. */
  public String usuario = "";

  /** Filtros correntes da aba de incidentes. */
  public Map<String, String> filtros = new LinkedHashMap<>();

  /** Incidente ou item aberto em detalhe, quando ha' um. */
  public String detalhe = "";

  public String url(String aba) {
    String u = urlsAbas.get(aba);
    return u == null ? "#" : u;
  }

  public String filtro(String nome) {
    String v = filtros.get(nome);
    return v == null ? "" : v;
  }

  /** Troca o marcador {@code __ID__} do modelo pelo identificador dado. */
  public String comId(String modelo, String identificador) {
    if (modelo == null || modelo.isEmpty()) {
      return "#";
    }
    return modelo.replace("__ID__", Html.codificar(identificador));
  }
}
