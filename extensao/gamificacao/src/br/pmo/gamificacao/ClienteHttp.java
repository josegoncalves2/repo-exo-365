package br.pmo.gamificacao;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Cliente HTTP do nucleo, com o destino AMARRADO ao host configurado.
 *
 * <p>Usa {@link java.net.http.HttpClient} do proprio JDK. Nenhuma biblioteca
 * externa: o nucleo tem de compilar e se provar com {@code javac} e {@code java}
 * e mais nada.
 *
 * <h2>As duas defesas que justificam esta classe existir</h2>
 *
 * <p><b>1. O host de destino e' fixado na URL base do conector.</b> Toda
 * requisicao e' montada como base + caminho, e o host resultante e' conferido
 * contra o host da base. Sem isso, um caminho contendo {@code //outro.host/} ou
 * uma barra a mais faria a URI resolver para outro servidor, e o cabecalho
 * {@code Authorization} do Github sairia para uma maquina qualquer. O token vale
 * para quem o receber: entregar ao servidor errado e' entregar a credencial.
 *
 * <p><b>2. Redirecionamento para outro host e' RECUSADO, nao seguido.</b> O
 * {@code HttpClient} e' criado com {@link HttpClient.Redirect#NEVER} e o
 * redirecionamento e' tratado aqui, a mao. Motivo: o comportamento padrao de
 * seguir redirecionamento e' exatamente o que transforma um servidor
 * comprometido -- ou so' mal configurado -- em ladrao de credencial. Ele
 * responde {@code 302 Location: https://coletor.exemplo/} e a biblioteca
 * reenvia a requisicao, com o mesmo cabecalho de autorizacao, para o novo
 * endereco. E' um roubo de token em uma linha de configuracao do outro lado.
 * Aqui, redirecionamento para o MESMO host e' seguido (troca de caminho e'
 * legitima e comum); para host diferente, a requisicao morre com
 * {@link DestinoInvalidoException} e nada e' reenviado.
 *
 * <p>Ha' teto de redirecionamentos para nao girar em ciclo, e tempo limite em
 * conexao e leitura: sem isso, um provedor que aceita a conexao e nunca responde
 * prende uma linha de execucao do portal indefinidamente.
 */
public final class ClienteHttp {

  /** Tempo limite padrao. Curto de proposito: isto roda dentro do portal. */
  public static final Duration ESPERA_PADRAO = Duration.ofSeconds(10);

  private static final int MAXIMO_REDIRECIONAMENTOS = 3;

  private final HttpClient http;

  private final Duration espera;

  public ClienteHttp() {
    this(ESPERA_PADRAO);
  }

  public ClienteHttp(Duration espera) {
    this.espera = espera;
    this.http = HttpClient.newBuilder()
        // NEVER e' o ponto principal desta classe. Ver o javadoc.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(espera)
        .build();
  }

  /**
   * Resolve {@code caminho} contra {@code base} e recusa qualquer mudanca de
   * host.
   *
   * @throws DestinoInvalidoException se a base for invalida, se o esquema nao
   *         for http/https, ou se o caminho levar a outro host
   */
  public static URI resolver(String base, String caminho) throws DestinoInvalidoException {
    if (base == null || base.isBlank()) {
      throw new DestinoInvalidoException("url.ausente", "URL base nao configurada");
    }
    URI uriBase;
    try {
      uriBase = new URI(base.trim());
    } catch (URISyntaxException e) {
      throw new DestinoInvalidoException("url.invalida", "URL base nao e' URI: " + e.getReason());
    }
    if (uriBase.getHost() == null) {
      throw new DestinoInvalidoException("url.invalida", "URL base sem host");
    }
    String esquema = uriBase.getScheme() == null
        ? "" : uriBase.getScheme().toLowerCase(Locale.ROOT);
    if (!esquema.equals("http") && !esquema.equals("https")) {
      throw new DestinoInvalidoException("url.esquema",
          "esquema '" + esquema + "' nao aceito; use http ou https");
    }
    URI alvo;
    try {
      alvo = uriBase.resolve(caminho == null ? "" : caminho);
    } catch (IllegalArgumentException e) {
      throw new DestinoInvalidoException("url.invalida", "caminho nao resolve");
    }
    conferirMesmoHost(uriBase, alvo, "caminho");
    return alvo;
  }

  /**
   * Envia e devolve a resposta, seguindo redirecionamento SO' dentro do mesmo
   * host.
   *
   * @param base URL base do conector; define o host permitido
   * @throws DestinoInvalidoException se houver desvio para outro host
   */
  public RespostaHttp enviar(String base, HttpRequest requisicao)
      throws IOException, InterruptedException, DestinoInvalidoException {
    URI uriBase;
    try {
      uriBase = new URI(base.trim());
    } catch (URISyntaxException e) {
      throw new DestinoInvalidoException("url.invalida", "URL base nao e' URI");
    }
    conferirMesmoHost(uriBase, requisicao.uri(), "requisicao");

    HttpRequest atual = requisicao;
    for (int salto = 0; salto <= MAXIMO_REDIRECIONAMENTOS; salto++) {
      HttpResponse<String> resposta =
          http.send(atual, HttpResponse.BodyHandlers.ofString());
      int status = resposta.statusCode();
      if (!ehRedirecionamento(status)) {
        return new RespostaHttp(status, resposta.body());
      }
      Optional<String> destino = resposta.headers().firstValue("Location");
      if (destino.isEmpty() || destino.get().isBlank()) {
        // Redirecionamento sem destino nao e' seguivel. Devolve como resposta
        // para o conector classificar, em vez de fingir que foi sucesso.
        return new RespostaHttp(status, resposta.body());
      }
      if (!"GET".equalsIgnoreCase(atual.method())) {
        // Redirecionamento em POST nao e' seguido, de proposito. 301/302/303
        // mandam trocar o metodo para GET, o que transformaria uma chamada
        // JSON-RPC num GET sem corpo cujo resultado nao significa nada; e
        // 307/308 exigiriam reenviar o corpo, que o HttpRequest ja' consumiu.
        // Seguir isso "na base do jeito" produziria sucesso falso.
        throw new DestinoInvalidoException("redirecionamento.em.post",
            "provedor redirecionou uma requisicao " + atual.method()
                + "; corpo nao e' reenviado");
      }
      URI proximo;
      try {
        proximo = atual.uri().resolve(destino.get().trim());
      } catch (IllegalArgumentException e) {
        throw new DestinoInvalidoException("redirecionamento.invalido",
            "Location nao e' URI valida");
      }
      // AQUI ESTA' A DEFESA: o destino do desvio e' medido contra a base.
      conferirMesmoHost(uriBase, proximo, "redirecionamento");
      atual = recriar(atual, proximo);
    }
    throw new DestinoInvalidoException("redirecionamento.excessivo",
        "mais de " + MAXIMO_REDIRECIONAMENTOS + " redirecionamentos");
  }

  private static boolean ehRedirecionamento(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /**
   * Recusa se host, esquema ou porta divergirem da base.
   *
   * <p>A PORTA ENTRA NA CONTA. {@code api.exemplo:443} e {@code api.exemplo:8080}
   * sao o mesmo nome e podem ser servidores diferentes -- em ambiente
   * corporativo, um deles costuma ser um proxy de depuracao que registra tudo
   * que passa, inclusive o cabecalho de autorizacao.
   *
   * <p>O ESQUEMA TAMBEM. Sem esta conferencia, um redirecionamento de
   * {@code https://} para {@code http://} no mesmo host rebaixaria a conexao e
   * o token sairia em texto claro pela rede.
   */
  private static void conferirMesmoHost(URI base, URI alvo, String origem)
      throws DestinoInvalidoException {
    String hostBase = base.getHost();
    String hostAlvo = alvo.getHost();
    if (hostAlvo == null) {
      throw new DestinoInvalidoException("destino.sem.host",
          "destino sem host (" + origem + ")");
    }
    if (!hostBase.equalsIgnoreCase(hostAlvo)) {
      throw new DestinoInvalidoException("destino.host.divergente",
          "destino '" + hostAlvo + "' diverge do host configurado '" + hostBase
              + "' (" + origem + "): credencial nao sai daqui");
    }
    String esquemaBase = base.getScheme() == null
        ? "" : base.getScheme().toLowerCase(Locale.ROOT);
    String esquemaAlvo = alvo.getScheme() == null
        ? "" : alvo.getScheme().toLowerCase(Locale.ROOT);
    if (!esquemaBase.equals(esquemaAlvo)) {
      throw new DestinoInvalidoException("destino.esquema.divergente",
          "esquema mudou de '" + esquemaBase + "' para '" + esquemaAlvo + "' (" + origem + ")");
    }
    if (portaEfetiva(base) != portaEfetiva(alvo)) {
      throw new DestinoInvalidoException("destino.porta.divergente",
          "porta mudou de " + portaEfetiva(base) + " para " + portaEfetiva(alvo)
              + " (" + origem + ")");
    }
  }

  private static int portaEfetiva(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** Recria a requisicao para o novo endereco, preservando metodo e cabecalhos. */
  private HttpRequest recriar(HttpRequest anterior, URI destino) {
    HttpRequest.Builder b = HttpRequest.newBuilder(destino).timeout(espera);
    anterior.headers().map().forEach((nome, valores) -> {
      for (String v : valores) {
        b.header(nome, v);
      }
    });
    // Sem corpo: as requisicoes de verificacao desta extensao sao todas GET.
    b.method(anterior.method(), HttpRequest.BodyPublishers.noBody());
    return b.build();
  }

  /** Construtor de requisicao GET com o tempo limite ja' aplicado. */
  public HttpRequest.Builder get(URI destino) {
    return HttpRequest.newBuilder(destino).timeout(espera).GET();
  }

  /**
   * Construtor de requisicao POST com corpo JSON.
   *
   * <p>Usado pelo conector EVM, cujo protocolo (JSON-RPC) so' existe em POST.
   * Redirecionamento em POST e' recusado -- ver {@link #enviar}.
   */
  public HttpRequest.Builder postJson(URI destino, String corpo) {
    return HttpRequest.newBuilder(destino)
        .timeout(espera)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(corpo,
            java.nio.charset.StandardCharsets.UTF_8));
  }
}
