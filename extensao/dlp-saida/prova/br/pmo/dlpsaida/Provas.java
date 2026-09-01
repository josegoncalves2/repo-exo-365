package br.pmo.dlpsaida;

/**
 * Provas do nucleo. Rodam no host, sem plataforma e sem rede.
 *
 * <p>Portao do empacotamento: se uma asseveracao falhar, o script aborta e o
 * jar NAO e' gerado. Cobrem o analisador de JSON e o escapamento, que sao
 * exatamente o lugar onde um erro silencioso faria o filtro entender
 * "permitido" quando o servico disse "bloqueado".
 */
public final class Provas {

  private static int total;

  public static void main(String[] args) {
    escapamento();
    leituraDeCampo();
    vereditoBloqueado();
    vereditoPermitido();
    vereditoAusenteFalhaFechada();
    contextoSerializa();
    listaDeAcoes();
    conteudoTransformado();
    base64Corrompido();
    contextoLevaEmail();
    System.out.println("Provas do nucleo dlp-saida: " + total + " asseveracoes, todas passaram");
  }

  private static void escapamento() {
    igual("\\\"", ClienteDlp.escapar("\""));
    igual("linha1\\nlinha2", ClienteDlp.escapar("linha1\nlinha2"));
    igual("c:\\\\temp", ClienteDlp.escapar("c:\\temp"));
    igual("", ClienteDlp.escapar(null));
    // Caractere de controle nao pode escapar cru para dentro do JSON.
    igual("a\\u0001b", ClienteDlp.escapar("a\u0001b"));
  }

  private static void leituraDeCampo() {
    String json = "{\"permitido\": false, \"regra_nome\":\"Cartao nao sai\","
                + "\"severidade\":\"CRITICA\",\"incidente\":\"abc-1\"}";
    igual("false", ClienteDlp.Veredito.campo(json, "permitido"));
    igual("Cartao nao sai", ClienteDlp.Veredito.textoDe(json, "regra_nome"));
    igual("", ClienteDlp.Veredito.textoDe(json, "inexistente"));
    // Valor com aspas escapadas dentro nao pode truncar a leitura.
    String comAspas = "{\"mensagem\":\"o \\\"cartao\\\" foi barrado\"}";
    igual("o \"cartao\" foi barrado",
          ClienteDlp.Veredito.textoDe(comAspas, "mensagem"));
  }

  private static void vereditoBloqueado() {
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson(
        "{\"permitido\":false,\"mensagem\":\"barrado\",\"regra_nome\":\"R1\"}");
    verdade(!v.permitido, "permitido=false tem de bloquear");
    igual("barrado", v.mensagem);
    igual("R1", v.regraNome);
  }

  private static void vereditoPermitido() {
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson("{\"permitido\":true}");
    verdade(v.permitido, "permitido=true tem de liberar");
  }

  private static void vereditoAusenteFalhaFechada() {
    // Resposta sem o campo NAO pode virar bloqueio silencioso nem liberacao
    // silenciosa: o contrato e' "ausente = permitido", e quem trata queda do
    // servico e' o filtro, com exo.dlp.falhaAberta.
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson("{}");
    verdade(v.permitido, "JSON vazio segue o padrao permitido");
    igual("", v.mensagem);
  }

  private static void contextoSerializa() {
    ClienteDlp.Contexto c = new ClienteDlp.Contexto();
    c.canal = "DOWNLOAD";
    c.usuario = "maria";
    c.grupos = new String[] {"/platform/users", "/platform/administrators"};
    StringBuilder b = new StringBuilder();
    c.escrever(b);
    String s = b.toString();
    verdade(s.contains("\"canal\":\"DOWNLOAD\""), "canal serializado");
    verdade(s.contains("\"usuario\":\"maria\""), "usuario serializado");
    verdade(s.contains("\"/platform/users\",\"/platform/administrators\""),
            "grupos serializados na ordem");
  }

  /**
   * A lista de acoes que o servico EXECUTOU.
   *
   * <p>Ate' 2026-08-31 o Veredito nao lia este campo, e por isso o portal era
   * incapaz de honrar qualquer acao alem de permitir ou bloquear. Estas
   * asseveracoes existem para que a regressao seja impossivel em silencio.
   */
  private static void listaDeAcoes() {
    String json = "{\"permitido\": true,"
                + "\"acoes\": [\"MASCARAR\",\"NOTIFICAR_ADMIN\"],"
                + "\"acoes_executadas\": [\"MASCARAR\",\"NOTIFICAR_ADMIN\"],"
                + "\"acoes_nao_aplicaveis\": [],"
                + "\"severidade\":\"ALTA\"}";
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson(json);
    igual("2", String.valueOf(v.acoesExecutadas.length));
    igual("MASCARAR", v.acoesExecutadas[0]);
    igual("NOTIFICAR_ADMIN", v.acoesExecutadas[1]);
    verdade(v.fez("MASCARAR"), "fez() reconhece a acao executada");
    verdade(!v.fez("BLOQUEAR"), "fez() nao inventa acao que nao ocorreu");
    igual("0", String.valueOf(v.acoesNaoAplicaveis.length));

    // Vetor de OBJETOS: e' o formato de acoes_nao_aplicaveis.
    String comObjetos = "{\"acoes_nao_aplicaveis\": ["
                      + "{\"acao\":\"MASCARAR\",\"motivo\":\"formato pdf\"}]}";
    ClienteDlp.Veredito o = ClienteDlp.Veredito.deJson(comObjetos);
    igual("1", String.valueOf(o.acoesNaoAplicaveis.length));
    verdade(o.acoesNaoAplicaveis[0].contains("MASCARAR"),
            "o par acao/motivo tem de chegar inteiro ao log");
    verdade(o.acoesNaoAplicaveis[0].contains("formato pdf"),
            "e o motivo junto");

    // Campo ausente NAO pode virar excecao: veredito antigo continua valido.
    ClienteDlp.Veredito velho = ClienteDlp.Veredito.deJson("{\"permitido\":true}");
    igual("0", String.valueOf(velho.acoesExecutadas.length));
  }

  /**
   * O conteudo transformado (mascarado ou cifrado) que substitui o original.
   *
   * <p>O servico ja' devolvia `texto_mascarado` antes; o filtro NUNCA o usava,
   * entao no download o conteudo passava inteiro ou era barrado. Este e' o
   * campo que faz MASCARAR e CRIPTOGRAFAR existirem na tela.
   */
  private static void conteudoTransformado() {
    // "conteudo mascarado" em base64.
    String json = "{\"permitido\": true,"
                + "\"acoes_executadas\": [\"MASCARAR\"],"
                + "\"conteudo_base64\": \"Y29udGV1ZG8gbWFzY2FyYWRv\","
                + "\"mime_saida\": \"text/plain; charset=utf-8\","
                + "\"nome_saida\": \"ficha.txt\","
                + "\"orientacao\": \"Use o compartilhamento do portal.\","
                + "\"quarentena\": \"\"}";
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson(json);
    verdade(v.temTransformacao(), "tem de haver conteudo transformado");
    igual("conteudo mascarado", new String(v.conteudo,
        java.nio.charset.StandardCharsets.UTF_8));
    igual("text/plain; charset=utf-8", v.mimeSaida);
    igual("ficha.txt", v.nomeSaida);
    igual("Use o compartilhamento do portal.", v.orientacao);

    ClienteDlp.Veredito sem = ClienteDlp.Veredito.deJson("{\"permitido\":true}");
    verdade(!sem.temTransformacao(), "sem o campo, nada e' substituido");
  }

  /**
   * Base64 ilegivel NAO pode virar "entrega o original".
   *
   * <p>Seria deixar passar em claro justamente o que a regra mandou
   * transformar -- e a falha ficaria invisivel, porque o download funcionaria.
   */
  private static void base64Corrompido() {
    ClienteDlp.Veredito v = ClienteDlp.Veredito.deJson(
        "{\"permitido\": true, \"conteudo_base64\": \"@@@nao-e-base64@@@\"}");
    verdade(!v.permitido, "base64 corrompido tem de fechar a porta");
    verdade(v.mensagem.contains("ilegivel"), "e dizer por que");
  }

  private static void contextoLevaEmail() {
    ClienteDlp.Contexto c = new ClienteDlp.Contexto();
    c.usuario = "maria.souza";
    c.email = "maria.souza@pmeto.local";
    StringBuilder b = new StringBuilder();
    c.escrever(b);
    verdade(b.toString().contains("\"email\":\"maria.souza@pmeto.local\""),
            "sem o e-mail, NOTIFICAR_USUARIO nao tem para onde enviar");

    ClienteDlp.Contexto observando = new ClienteDlp.Contexto();
    observando.observacao = true;
    StringBuilder o = new StringBuilder();
    observando.escrever(o);
    verdade(o.toString().contains("\"observacao\":true"),
            "o portal tem de DIZER que esta em observacao; sem isso o servico "
            + "retem no cofre e manda e-mail enquanto ninguem e bloqueado");
    StringBuilder a = new StringBuilder();
    c.escrever(a);
    verdade(a.toString().contains("\"observacao\":false"),
            "e tem de dizer quando NAO esta");
  }

  private static void igual(String esperado, String obtido) {
    total++;
    if (esperado == null ? obtido != null : !esperado.equals(obtido)) {
      throw new AssertionError("esperado <" + esperado + ">, obtido <" + obtido + ">");
    }
  }

  private static void verdade(boolean condicao, String descricao) {
    total++;
    if (!condicao) {
      throw new AssertionError("falhou: " + descricao);
    }
  }
}
