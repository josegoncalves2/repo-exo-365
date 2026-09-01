package br.pmo.dlpconsole;

import java.util.List;
import java.util.Map;

/**
 * Montagem de HTML com escape OBRIGATORIO na fronteira.
 *
 * <p><b>POR QUE ESTA CLASSE EXISTE.</b> Tudo o que esta tela mostra vem de
 * fora: nome de arquivo enviado por um usuario, destino de um link, texto de
 * anotacao, mensagem de regra. Um console de seguranca que renderiza isso sem
 * escapar entrega XSS a quem sabe que o incidente vai ser aberto por um
 * administrador -- e um atacante controla o nome do arquivo que ele mesmo
 * subiu. Por isso NAO ha' concatenacao direta de valor externo na tela: tudo
 * passa por {@link #t(String)} ou por um dos ajudantes daqui.
 *
 * <p>O escape cobre {@code & < > " '} e tambem {@code /}, que fecha tag em
 * navegadores antigos dentro de contexto de script.
 */
public final class Html {

  private Html() {
  }

  /** Escapa texto para uso em corpo de elemento ou valor de atributo. */
  public static String t(String valor) {
    if (valor == null || valor.isEmpty()) {
      return "";
    }
    StringBuilder b = new StringBuilder(valor.length() + 16);
    for (int i = 0; i < valor.length(); i++) {
      char c = valor.charAt(i);
      switch (c) {
        case '&': b.append("&amp;"); break;
        case '<': b.append("&lt;"); break;
        case '>': b.append("&gt;"); break;
        case '"': b.append("&quot;"); break;
        case '\'': b.append("&#39;"); break;
        case '/': b.append("&#47;"); break;
        default: b.append(c);
      }
    }
    return b.toString();
  }

  /** Texto cortado no limite, com reticencia. Coluna de tabela nao estica. */
  public static String curto(String valor, int limite) {
    if (valor == null) {
      return "";
    }
    String v = valor.trim();
    return v.length() <= limite ? t(v) : t(v.substring(0, limite - 1)) + "&hellip;";
  }

  /**
   * Momento ISO em algo que uma pessoa le: "31/08 14:37".
   *
   * <p>A saida e' montada com DIGITOS conferidos um a um, e por isso nao passa
   * pelo escape -- que transformaria a barra da data em {@code &#47;} e deixaria
   * "31&#47;08" na tela. Qualquer caractere fora de digito faz cair no escape
   * do texto inteiro: valor estranho vindo do servico nao vira marcacao.
   */
  public static String momento(String iso) {
    if (iso == null || iso.length() < 16) {
      return t(iso);
    }
    String dia = iso.substring(8, 10);
    String mes = iso.substring(5, 7);
    String hora = iso.substring(11, 16);
    if (!soDigitos(dia) || !soDigitos(mes) || !soDigitos(hora.substring(0, 2))
        || !soDigitos(hora.substring(3, 5)) || hora.charAt(2) != ':') {
      return t(iso);
    }
    return dia + "/" + mes + " " + hora;
  }

  private static boolean soDigitos(String v) {
    for (int i = 0; i < v.length(); i++) {
      if (!Character.isDigit(v.charAt(i))) {
        return false;
      }
    }
    return v.length() > 0;
  }

  public static String etiqueta(String texto, String cor) {
    return "<span class=\"pmo-eti\" style=\"background:" + t(cor) + "\">"
           + t(texto) + "</span>";
  }

  /** Cor por severidade. Vermelho so' para o que exige acao agora. */
  public static String corSeveridade(String severidade) {
    switch (severidade == null ? "" : severidade) {
      case "CRITICA": return "#b3261e";
      case "ALTA": return "#c25e00";
      case "MEDIA": return "#7a6000";
      case "BAIXA": return "#4a6572";
      default: return "#6b6b6b";
    }
  }

  public static String corEstado(String estado) {
    switch (estado == null ? "" : estado) {
      case "NOVO": return "#1a5fb4";
      case "EM_ANALISE": return "#a35200";
      case "ESCALADO": return "#b3261e";
      case "CONFIRMADO": return "#5e2750";
      case "FALSO_POSITIVO": return "#4a6572";
      case "RESOLVIDO": return "#1b5e20";
      default: return "#6b6b6b";
    }
  }

  public static String campoOculto(String nome, String valor) {
    return "<input type=\"hidden\" name=\"" + t(nome) + "\" value=\""
           + t(valor) + "\">";
  }

  public static String opcoes(List<String> valores, String selecionado) {
    StringBuilder b = new StringBuilder();
    for (String v : valores) {
      b.append("<option value=\"").append(t(v)).append('"')
       .append(v.equals(selecionado) ? " selected" : "").append('>')
       .append(t(v)).append("</option>");
    }
    return b.toString();
  }

  /** Numero com separador de milhar, para o painel nao virar sopa de digitos. */
  public static String numero(long valor) {
    String bruto = Long.toString(Math.abs(valor));
    StringBuilder b = new StringBuilder();
    int contador = 0;
    for (int i = bruto.length() - 1; i >= 0; i--) {
      b.append(bruto.charAt(i));
      if (++contador % 3 == 0 && i > 0) {
        b.append('.');
      }
    }
    if (valor < 0) {
      b.append('-');
    }
    return b.reverse().toString();
  }

  /** Cartao de numero do painel. */
  public static String cartao(String rotulo, String valor, String detalhe,
                              String cor) {
    return "<div class=\"pmo-cartao\" style=\"border-top:3px solid " + t(cor)
           + "\"><div class=\"pmo-cartao-n\">" + t(valor) + "</div>"
           + "<div class=\"pmo-cartao-r\">" + t(rotulo) + "</div>"
           + (detalhe == null || detalhe.isEmpty() ? ""
              : "<div class=\"pmo-cartao-d\">" + t(detalhe) + "</div>")
           + "</div>";
  }

  public static String vazio(String mensagem) {
    return "<p class=\"pmo-vazio\">" + t(mensagem) + "</p>";
  }

  /** Linha "rotulo: valor" da tela de detalhe. */
  public static String par(String rotulo, String valor) {
    return "<div class=\"pmo-par\"><dt>" + t(rotulo) + "</dt><dd>"
           + (valor == null || valor.isEmpty() ? "&mdash;" : t(valor))
           + "</dd></div>";
  }

  public static String parBruto(String rotulo, String htmlJaEscapado) {
    return "<div class=\"pmo-par\"><dt>" + t(rotulo) + "</dt><dd>"
           + htmlJaEscapado + "</dd></div>";
  }

  /** Junta os valores de uma chave de cada item de uma lista de objetos. */
  public static String juntarCampo(List<Object> itens, String chave,
                                   String separador) {
    StringBuilder b = new StringBuilder();
    for (Object o : itens) {
      String v = Json.texto(o, chave);
      if (v.isEmpty()) {
        continue;
      }
      if (b.length() > 0) {
        b.append(separador);
      }
      b.append(v);
    }
    return b.toString();
  }

  /** Consulta de URL a partir de pares chave/valor ja' codificados. */
  public static String consulta(Map<String, String> parametros) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, String> e : parametros.entrySet()) {
      if (e.getValue() == null || e.getValue().isEmpty()) {
        continue;
      }
      b.append(b.length() == 0 ? "" : "&")
       .append(codificar(e.getKey())).append('=').append(codificar(e.getValue()));
    }
    return b.toString();
  }

  public static String codificar(String valor) {
    if (valor == null) {
      return "";
    }
    try {
      return java.net.URLEncoder.encode(valor, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return "";
    }
  }
}
