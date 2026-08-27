package br.pmo.mfa;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import br.pmo.mfa.CatalogoZonas.Decisao;
import br.pmo.mfa.CatalogoZonas.QuandoIndeterminado;

/**
 * Provas do nucleo de zonas. Sem JUnit de proposito: um servidor sem saida
 * para a internet nao baixa jar para provar aritmetica de bits.
 *
 * <p>Roda com {@code javac} e {@code java} e mais nada. Codigo de saida != 0
 * aborta o empacotamento.
 */
public final class Provas {

  private static int ok = 0;
  private static int falhas = 0;

  private static void checa(String nome, boolean condicao) {
    if (condicao) {
      ok++;
      System.out.println("   ok   " + nome);
    } else {
      falhas++;
      System.out.println("  FALHOU " + nome);
    }
  }

  private static void secao(String titulo) {
    System.out.println();
    System.out.println("== " + titulo);
  }

  public static void main(String[] args) {
    provaCidrBasico();
    provaMascaraQuebrada();
    provaArmadilhaDoPrefixoTextual();
    provaIpv6EMapeados();
    provaFaixaInvalidaLanca();
    provaXffForjado();
    provaXffLegitimo();
    provaPrecedenciaDoCatalogo();
    provaEstadoInerte();
    provaIndeterminado();
    provaAchadosDoFiscal();

    System.out.println();
    System.out.println("RESULTADO: " + (ok + falhas) + " asseveracoes, " + falhas + " falhas.");
    System.exit(falhas == 0 ? 0 : 1);
  }

  // ---------------------------------------------------------------------------

  private static void provaCidrBasico() {
    secao("CIDR basico");
    Zona z = Zona.de("192.168.1.0/24");
    checa("192.168.1.1 pertence a 192.168.1.0/24", z.contem("192.168.1.1"));
    checa("192.168.1.255 pertence", z.contem("192.168.1.255"));
    checa("192.168.2.1 NAO pertence", !z.contem("192.168.2.1"));
    checa("endereco solto vira /32", Zona.de("10.0.0.5").contem("10.0.0.5"));
    checa("e /32 nao pega o vizinho", !Zona.de("10.0.0.5").contem("10.0.0.6"));
    checa("faixa 0.0.0.0/0 pega tudo", Zona.de("0.0.0.0/0").contem("8.8.8.8"));
  }

  private static void provaMascaraQuebrada() {
    secao("Mascara que nao termina em octeto cheio");
    // /26 = 4 sub-redes de 64 enderecos dentro do mesmo terceiro octeto.
    Zona z = Zona.de("192.168.1.64/26");
    checa("192.168.1.64 pertence a /26", z.contem("192.168.1.64"));
    checa("192.168.1.127 pertence (ultimo da faixa)", z.contem("192.168.1.127"));
    checa("192.168.1.63 NAO pertence (anterior)", !z.contem("192.168.1.63"));
    checa("192.168.1.128 NAO pertence (proxima faixa)", !z.contem("192.168.1.128"));

    Zona doze = Zona.de("172.16.0.0/12");
    checa("172.16.0.1 pertence a /12", doze.contem("172.16.0.1"));
    checa("172.31.255.254 pertence a /12", doze.contem("172.31.255.254"));
    checa("172.32.0.1 NAO pertence a /12", !doze.contem("172.32.0.1"));
    checa("172.15.255.254 NAO pertence a /12", !doze.contem("172.15.255.254"));

    // Endereco de host escrito no lugar do endereco de rede.
    checa("192.168.1.7/24 e' normalizado para a rede",
          Zona.de("192.168.1.7/24").contem("192.168.1.200"));
  }

  private static void provaArmadilhaDoPrefixoTextual() {
    secao("A armadilha que motiva a classe: comparacao textual");
    Zona z = Zona.de("192.168.1.0/24");
    // "192.168.10.5".startsWith("192.168.1.") e' true, e uma rede inteira
    // entraria por engano.
    checa("192.168.10.5 NAO pertence a 192.168.1.0/24 (startsWith diria que sim)",
          !z.contem("192.168.10.5"));
    checa("192.168.11.5 NAO pertence", !z.contem("192.168.11.5"));
    checa("192.168.199.5 NAO pertence", !z.contem("192.168.199.5"));
  }

  private static void provaIpv6EMapeados() {
    secao("IPv6 e IPv4 mapeado");
    Zona v4 = Zona.de("192.168.1.0/24");
    checa("::ffff:192.168.1.5 casa com regra IPv4 (desmapeamento)",
          v4.contem("::ffff:192.168.1.5"));
    checa("::ffff:192.168.2.5 NAO casa", !v4.contem("::ffff:192.168.2.5"));

    Zona v6 = Zona.de("2001:db8::/32");
    checa("2001:db8::1 pertence a 2001:db8::/32", v6.contem("2001:db8::1"));
    checa("2001:db9::1 NAO pertence", !v6.contem("2001:db9::1"));
    checa("IPv4 nao casa com faixa IPv6", !v6.contem("192.168.1.1"));
    checa("IPv6 nao casa com faixa IPv4", !v4.contem("2001:db8::1"));
    checa("escopo de interface e' ignorado", Zona.de("fe80::/10").contem("fe80::1%eth0"));
  }

  private static void provaFaixaInvalidaLanca() {
    secao("Faixa invalida aparece como erro, nao vira regra silenciosa");
    checa("prefixo acima do maximo lanca", lanca("192.168.1.0/33"));
    checa("prefixo negativo lanca", lanca("192.168.1.0/-1"));
    checa("prefixo nao numerico lanca", lanca("192.168.1.0/abc"));
    checa("octeto fora da faixa lanca", lanca("999.1.1.1/24"));
    checa("nome de maquina NAO e' aceito (evitaria DNS no caminho)",
          lanca("intranet.pmo.gov.br/32"));
    checa("texto vazio lanca", lanca("   "));
  }

  private static boolean lanca(String texto) {
    try {
      Zona.de(texto);
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  // ---------------------------------------------------------------------------

  private static void provaXffForjado() {
    secao("X-Forwarded-For FORJADO nao muda a zona (o furo principal)");

    List<Zona> proxies = Collections.singletonList(Zona.de("172.18.0.0/16"));
    OrigemRequisicao origem = new OrigemRequisicao(proxies);

    // Atacante fala DIRETO com o portal (nao passou pelo proxy) e inventa XFF.
    checa("cliente direto: XFF forjado e' ignorado, vale o remoteAddr",
          "203.0.113.9".equals(origem.resolver("203.0.113.9", "192.168.1.10")));

    // Atacante passa pelo proxy e injeta uma entrada a' esquerda, tentando se
    // declarar na rede interna. O nginx ANEXA o endereco real a' direita.
    checa("via proxy: entrada forjada a' esquerda e' descartada; vale a da direita",
          "203.0.113.9".equals(origem.resolver("172.18.0.5", "192.168.1.10, 203.0.113.9")));

    checa("varias entradas forjadas nao mudam nada",
          "203.0.113.9".equals(
              origem.resolver("172.18.0.5", "10.0.0.1, 192.168.1.10, 127.0.0.1, 203.0.113.9")));

    // Sem proxy declarado, XFF nunca vale.
    OrigemRequisicao semProxy = new OrigemRequisicao(Collections.<Zona>emptyList());
    checa("sem proxy declarado, XFF e' sempre ignorado",
          "203.0.113.9".equals(semProxy.resolver("203.0.113.9", "192.168.1.10")));
    checa("sem proxy declarado, nem a cadeia inteira vale",
          "172.18.0.5".equals(semProxy.resolver("172.18.0.5", "192.168.1.10, 203.0.113.9")));
  }

  private static void provaXffLegitimo() {
    secao("X-Forwarded-For legitimo e' respeitado");

    OrigemRequisicao origem = new OrigemRequisicao(
        Arrays.asList(Zona.de("172.18.0.0/16"), Zona.de("127.0.0.1/32")));

    checa("um proxy: devolve o cliente real",
          "192.168.1.42".equals(origem.resolver("172.18.0.5", "192.168.1.42")));

    checa("dois proxies nossos encadeados: devolve o cliente real",
          "192.168.1.42".equals(origem.resolver("172.18.0.5", "192.168.1.42, 172.18.0.9")));

    checa("cadeia so' de proxies nossos: devolve o proxy",
          "172.18.0.5".equals(origem.resolver("172.18.0.5", "172.18.0.9, 127.0.0.1")));

    checa("XFF vazio vindo do proxy: devolve o proxy",
          "172.18.0.5".equals(origem.resolver("172.18.0.5", "")));

    checa("XFF nulo vindo do proxy: devolve o proxy",
          "172.18.0.5".equals(origem.resolver("172.18.0.5", null)));

    // Esta asseveracao AFIRMAVA o comportamento inseguro ate' 2026-08-27:
    // entrada ilegivel devolvia o endereco do proxy, e como o proxy costuma
    // estar em faixa isenta, isso zerava a exigencia de segundo fator. Uma
    // prova que cristaliza a falha aberta e' pior que prova nenhuma: da'
    // confianca. Agora afirma o oposto, que e' o correto.
    checa("entrada ilegivel devolve INDETERMINADO (falha FECHADA, nao o proxy)",
          origem.resolver("172.18.0.5", "192.168.1.42, lixo-nao-e-ip") == null);

    checa("endereco com porta e' aceito sem a porta",
          "192.168.1.42".equals(origem.resolver("172.18.0.5", "192.168.1.42:53122")));

    checa("IPv6 entre colchetes com porta e' aceito",
          "2001:db8::1".equals(origem.resolver("172.18.0.5", "[2001:db8::1]:443")));

    checa("remoteAddr nulo devolve nulo (origem indeterminada)",
          origem.resolver(null, "192.168.1.42") == null);
  }

  private static void provaPrecedenciaDoCatalogo() {
    secao("Precedencia: isencao vence exigencia");

    CatalogoZonas catalogo = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0"),
        CatalogoZonas.interpretarLista("192.168.1.0/24"),
        QuandoIndeterminado.EXIGIR);

    Decisao dentro = catalogo.decidir("192.168.1.50");
    checa("endereco na zona isenta NAO exige, mesmo com exigencia global",
          !dentro.exigeSegundoFator());
    checa("e o motivo cita a zona isenta", dentro.getMotivo().contains("isenta"));

    Decisao fora = catalogo.decidir("203.0.113.9");
    checa("endereco fora da isencao exige", fora.exigeSegundoFator());
    checa("e o motivo cita a zona protegida", fora.getMotivo().contains("protegida"));

    CatalogoZonas soInterna = new CatalogoZonas(
        CatalogoZonas.interpretarLista("192.168.1.0/24"),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.EXIGIR);
    checa("endereco fora de qualquer zona protegida nao exige",
          !soInterna.decidir("8.8.8.8").exigeSegundoFator());
  }

  private static void provaEstadoInerte() {
    secao("Estado inerte: sem zona configurada, a extensao nao opina");

    CatalogoZonas vazio = new CatalogoZonas(
        CatalogoZonas.interpretarLista(""),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.EXIGIR);

    checa("catalogo vazio se declara inerte", vazio.estaInerte());
    checa("inerte NAO exige de ninguem", !vazio.decidir("203.0.113.9").exigeSegundoFator());
    checa("inerte nao exige nem com origem indeterminada",
          !vazio.decidir(null).exigeSegundoFator());
    checa("e o motivo diz que esta' inerte",
          vazio.decidir("203.0.113.9").getMotivo().contains("inerte"));
  }

  /**
   * Achados de revisao adversarial em 2026-08-27. Cada um foi PROVADO
   * explorável antes de ser corrigido; ficam aqui para nao voltarem.
   */
  private static void provaAchadosDoFiscal() {
    secao("Achados do fiscal (regressao)");

    // ACHADO 3 (ALTA): nome de maquina formado so' por caracteres hexadecimais
    // atravessava o reconhecedor e ia parar em getByName, que fazia DNS
    // SINCRONO no caminho da requisicao ; quem controlasse o dominio escolhia
    // a propria zona, e um servidor de DNS que nao responde prendia a thread.
    checa("hostname hexadecimal NAO e' aceito como endereco",
          !Zona.enderecoValido("f00dbabe.cafe.ac"));
    checa("hostname hexadecimal NAO e' aceito como faixa", lanca("f00dbabe.cafe.ac/32"));
    checa("hostname hexadecimal nao casa faixa nenhuma",
          !Zona.de("0.0.0.0/0").contem("f00dbabe.cafe.ac"));
    checa("dominio .de tambem nao passa", !Zona.enderecoValido("dead.beef.de"));

    // ACHADO 7 (MEDIA): o JDK aceita formas abreviadas e decimais de IPv4.
    // Alem do casamento inesperado, o texto gravado na auditoria deixava de
    // ser o mesmo que nginx/iptables/SIEM entendem.
    checa("10.5 NAO e' 10.0.0.5", !Zona.de("10.0.0.0/8").contem("10.5"));
    checa("10.0.5 NAO e' 10.0.0.5", !Zona.de("10.0.0.0/8").contem("10.0.5"));
    checa("2130706433 NAO e' 127.0.0.1", !Zona.de("127.0.0.0/8").contem("2130706433"));
    checa("zero a esquerda e' recusado (seria octal em outros interpretadores)",
          !Zona.de("10.0.0.0/8").contem("0000010.0.0.5"));
    checa("mas a forma canonica segue valendo", Zona.de("10.0.0.0/8").contem("10.0.0.5"));
    checa("e o zero sozinho continua valido", Zona.de("0.0.0.0/8").contem("0.0.0.1"));

    // ACHADO 4 (ALTA): o ramo dos colchetes devolvia sem validar ; o MESMO
    // furo que a validacao ao lado dizia ter fechado, por caminho paralelo.
    OrigemRequisicao origem = new OrigemRequisicao(
        Collections.singletonList(Zona.de("172.18.0.0/16")));
    checa("[lixo] entre colchetes NAO vira endereco",
          origem.resolver("172.18.0.5", "[lixo]") == null);
    checa("[nao-e-ip] tambem nao", origem.resolver("172.18.0.5", "[nao-e-ip]") == null);
    checa("[../etc] tambem nao", origem.resolver("172.18.0.5", "[../etc]") == null);
    checa("[] vazio tambem nao", origem.resolver("172.18.0.5", "[]") == null);
    checa("mas IPv6 legitimo entre colchetes segue valendo",
          "2001:db8::1".equals(origem.resolver("172.18.0.5", "[2001:db8::1]:443")));

    // ACHADO 6 (MEDIA): entrada ilegivel devolvia o endereco do PROPRIO proxy.
    // Como o proxy costuma estar em faixa isenta, tornar a entrada ilegivel
    // zerava a exigencia. Agora devolve null -> indeterminado -> EXIGIR.
    checa("entrada ilegivel devolve INDETERMINADO, nao o proxy",
          origem.resolver("172.18.0.5", "192.168.1.42, lixo-nao-e-ip") == null);

    CatalogoZonas comIndeterminado = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0,::/0"),
        CatalogoZonas.interpretarLista("172.18.0.0/16"),
        QuandoIndeterminado.EXIGIR);
    checa("e indeterminado EXIGE, em vez de cair na zona isenta do proxy",
          comIndeterminado.decidir(origem.resolver("172.18.0.5", "[lixo]")).exigeSegundoFator());

    // ACHADO 5 (MEDIA): 0.0.0.0/0 nao cobre IPv6, e o nginx escuta em [::].
    CatalogoZonas soV4 = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0"),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.EXIGIR);
    checa("0.0.0.0/0 sozinho ISENTA a pilha IPv6 inteira (por isso o exemplo mudou)",
          !soV4.decidir("2804:14d:1::9").exigeSegundoFator());

    CatalogoZonas ambas = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0,::/0"),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.EXIGIR);
    checa("com ::/0 junto, IPv6 passa a exigir",
          ambas.decidir("2804:14d:1::9").exigeSegundoFator());
    checa("e IPv4 continua exigindo", ambas.decidir("203.0.113.9").exigeSegundoFator());
  }

  private static void provaIndeterminado() {
    secao("Origem indeterminada");

    CatalogoZonas exigente = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0"),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.EXIGIR);
    checa("indeterminado com politica EXIGIR exige",
          exigente.decidir(null).exigeSegundoFator());

    CatalogoZonas permissivo = new CatalogoZonas(
        CatalogoZonas.interpretarLista("0.0.0.0/0"),
        CatalogoZonas.interpretarLista(""),
        QuandoIndeterminado.ISENTAR);
    checa("indeterminado com politica ISENTAR nao exige",
          !permissivo.decidir(null).exigeSegundoFator());

    checa("configuracao invalida cai no padrao EXIGIR, nao em isencao",
          QuandoIndeterminado.de("BANANA", QuandoIndeterminado.EXIGIR)
              == QuandoIndeterminado.EXIGIR);
    checa("configuracao vazia cai no padrao",
          QuandoIndeterminado.de("", QuandoIndeterminado.EXIGIR)
              == QuandoIndeterminado.EXIGIR);
  }
}
