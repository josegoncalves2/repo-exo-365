package br.pmo.mfa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Descobre de qual endereco a requisicao REALMENTE veio, atras de proxy.
 *
 * <h2>O buraco que esta classe existe para tapar</h2>
 *
 * O portal roda atras do nginx. {@code request.getRemoteAddr()} devolve, para
 * TODA requisicao, o endereco do proxy ; nunca o do usuario. Uma politica de
 * zona baseada nisso colocaria o mundo inteiro na mesma zona e seria inutil.
 *
 * A saida obvia e' ler {@code X-Forwarded-For}. E a saida obvia, feita de forma
 * obvia, e' <b>pior do que nao ter a funcionalidade</b>: esse cabecalho e'
 * escrito pelo CLIENTE. Quem pega a primeira entrada da lista aceita que
 * qualquer pessoa mande
 *
 * <pre>
 *   X-Forwarded-For: 192.168.1.10
 * </pre>
 *
 * e se declare dentro da rede interna. Se a rede interna for zona isenta de
 * 2FA ; que e' exatamente o desenho que todo mundo escreve ; entao o segundo
 * fator vira opcional para quem souber usar um cabecalho HTTP. O controle
 * passa a existir so' para quem nao esta' atacando.
 *
 * <h2>A regra correta</h2>
 *
 * <ol>
 *   <li>Se {@code getRemoteAddr()} NAO for um proxy confiavel declarado, o
 *       cabecalho e' <b>integralmente ignorado</b>. Nao ha' proxy no caminho:
 *       qualquer XFF ali e' invencao do cliente.</li>
 *   <li>Se FOR proxy confiavel, percorre-se a cadeia <b>da direita para a
 *       esquerda</b> e devolve-se a primeira entrada que NAO seja proxy
 *       confiavel. Esse e' o ultimo endereco que um proxy nosso realmente
 *       observou; tudo a' esquerda dele foi escrito por quem esta' antes na
 *       cadeia, e nao ha' como verificar.</li>
 * </ol>
 *
 * Da esquerda para a direita seria confiar no que o cliente escreveu. Da
 * direita para a esquerda so' se confia no que a nossa propria borda anotou.
 *
 * <h2>Sem proxy confiavel declarado</h2>
 *
 * A lista nasce VAZIA, e vazia significa "ignore XFF sempre e use
 * getRemoteAddr()". Isso e' seguro por construcao (nada e' forjavel) e ao mesmo
 * tempo torna a politica de zona inofensiva ate' alguem declarar a borda ; que
 * e' o comportamento certo para uma funcionalidade de seguranca que ninguem
 * configurou ainda.
 */
public final class OrigemRequisicao {

  /** Cabecalhos consultados, em ordem. O primeiro presente vence. */
  private static final List<String> CABECALHOS =
      Collections.unmodifiableList(java.util.Arrays.asList("X-Forwarded-For", "X-Real-IP"));

  private final List<Zona> proxiesConfiaveis;

  public OrigemRequisicao(List<Zona> proxiesConfiaveis) {
    List<Zona> copia = new ArrayList<>();
    if (proxiesConfiaveis != null) {
      for (Zona zona : proxiesConfiaveis) {
        if (zona != null) {
          copia.add(zona);
        }
      }
    }
    this.proxiesConfiaveis = Collections.unmodifiableList(copia);
  }

  public List<Zona> getProxiesConfiaveis() {
    return proxiesConfiaveis;
  }

  public boolean temProxyDeclarado() {
    return !proxiesConfiaveis.isEmpty();
  }

  private boolean ehProxyConfiavel(String endereco) {
    for (Zona zona : proxiesConfiaveis) {
      if (zona.contem(endereco)) {
        return true;
      }
    }
    return false;
  }

  /**
   * O endereco de origem que a politica deve julgar.
   *
   * @param enderecoRemoto     {@code request.getRemoteAddr()}
   * @param valorCabecalhoXff  conteudo de X-Forwarded-For (ou X-Real-IP), pode
   *                           ser nulo
   * @return o endereco a julgar, ou {@code null} quando nao ha' como
   *         determina-lo. Nulo NAO significa "libere": significa "nao sei", e
   *         quem decide o que fazer com isso e' {@link CatalogoZonas}.
   */
  public String resolver(String enderecoRemoto, String valorCabecalhoXff) {
    String remoto = normalizar(enderecoRemoto);

    // Sem proxy declarado, ou requisicao que nao veio de um proxy nosso:
    // o cabecalho nao tem valor probatorio nenhum.
    if (remoto == null || !ehProxyConfiavel(remoto)) {
      return remoto;
    }

    if (valorCabecalhoXff == null || valorCabecalhoXff.trim().isEmpty()) {
      // Veio da borda mas sem cadeia. E' o proprio proxy falando (health check,
      // por exemplo). O endereco dele e' a resposta honesta.
      return remoto;
    }

    String[] cadeia = valorCabecalhoXff.split(",");
    for (int i = cadeia.length - 1; i >= 0; i--) {
      String candidato = normalizar(cadeia[i]);
      if (candidato == null) {
        // Entrada ilegivel na cadeia. Nao se pula em silencio: dali para a
        // esquerda nada mais e' confiavel, porque nao da' para saber quem
        // escreveu o que. Para na borda conhecida.
        return remoto;
      }
      if (!ehProxyConfiavel(candidato)) {
        return candidato;
      }
    }

    // Cadeia inteira composta de proxies nossos: a origem e' a nossa propria
    // infraestrutura.
    return remoto;
  }

  /** Lista de cabecalhos que o filtro deve consultar, em ordem de precedencia. */
  public static List<String> cabecalhosConsultados() {
    return CABECALHOS;
  }

  private static String normalizar(String bruto) {
    if (bruto == null) {
      return null;
    }
    String limpo = bruto.trim();
    if (limpo.isEmpty()) {
      return null;
    }
    // Alguns proxies anotam "endereco:porta". A porta nao participa de zona.
    // Cuidado: IPv6 tem dois-pontos no proprio endereco, entao so' se corta
    // quando ha' EXATAMENTE um, ou quando esta' na forma [ipv6]:porta.
    if (limpo.startsWith("[")) {
      int fecha = limpo.indexOf(']');
      if (fecha > 0) {
        return limpo.substring(1, fecha);
      }
      return null;
    }
    int primeiro = limpo.indexOf(':');
    if (primeiro >= 0 && primeiro == limpo.lastIndexOf(':')) {
      return limpo.substring(0, primeiro);
    }
    return limpo;
  }
}
