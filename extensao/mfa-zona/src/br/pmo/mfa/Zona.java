package br.pmo.mfa;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Uma faixa de rede em notacao CIDR, e o teste de pertinencia.
 *
 * <p><b>POR QUE ESTA CLASSE NAO USA String.startsWith.</b> A tentacao obvia de
 * "esta' na rede interna?" e' {@code ip.startsWith("192.168.1.")}. Isso e'
 * falso de tres maneiras, e todas as tres sao explorareis:
 *
 * <ol>
 *   <li>{@code "192.168.10.5".startsWith("192.168.1.")} e' FALSO em intencao e
 *       VERDADEIRO em execucao ; uma rede vizinha inteira entra por engano;</li>
 *   <li>prefixo nao expressa mascara que nao termina em octeto cheio: /26, /22
 *       e /12 sao a norma em rede corporativa, e nenhum deles cabe em
 *       comparacao textual;</li>
 *   <li>a mesma maquina se apresenta como {@code 192.168.1.5},
 *       {@code ::ffff:192.168.1.5} ou {@code 0:0:0:0:0:ffff:c0a8:105} conforme
 *       a pilha ; texto diferente, endereco identico.</li>
 * </ol>
 *
 * <p>Aqui a comparacao e' feita nos BITS do endereco, que e' o unico lugar onde
 * "pertencer a uma rede" tem definicao. Enderecos IPv4 mapeados em IPv6
 * ({@code ::ffff:a.b.c.d}) sao normalizados para IPv4 antes da comparacao ;
 * sem isso um cliente que chegasse pela pilha IPv6 escaparia silenciosamente
 * de uma regra escrita em IPv4.
 *
 * <p>Imutavel e sem I/O: nao resolve nome, so' interpreta endereco literal.
 * Resolucao de DNS aqui seria uma chamada de rede no caminho de CADA requisicao
 * e um vetor de negacao de servico.
 */
public final class Zona {

  private final byte[] rede;
  private final int bitsPrefixo;
  private final String textoOriginal;

  private Zona(byte[] rede, int bitsPrefixo, String textoOriginal) {
    this.rede = rede;
    this.bitsPrefixo = bitsPrefixo;
    this.textoOriginal = textoOriginal;
  }

  /**
   * Interpreta {@code 192.168.1.0/24}, {@code 10.0.0.0/8}, {@code 2001:db8::/32}
   * ou um endereco solto ({@code 192.168.1.7}, tratado como /32 ou /128).
   *
   * @throws IllegalArgumentException se o texto nao for uma faixa valida. E'
   *         proposital que NAO devolva null nem uma zona "que nao casa nada":
   *         faixa escrita errada na configuracao tem de aparecer como erro no
   *         boot, e nao virar uma regra de seguranca que silenciosamente nunca
   *         se aplica.
   */
  public static Zona de(String texto) {
    if (texto == null) {
      throw new IllegalArgumentException("faixa nula");
    }
    String limpo = texto.trim();
    if (limpo.isEmpty()) {
      throw new IllegalArgumentException("faixa vazia");
    }

    String parteEndereco = limpo;
    Integer prefixoDeclarado = null;

    int barra = limpo.lastIndexOf('/');
    if (barra >= 0) {
      parteEndereco = limpo.substring(0, barra).trim();
      String partePrefixo = limpo.substring(barra + 1).trim();
      try {
        prefixoDeclarado = Integer.valueOf(partePrefixo);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("prefixo nao numerico em '" + limpo + "'");
      }
    }

    byte[] bytes = interpretarEndereco(parteEndereco, limpo);
    int maximo = bytes.length * 8;
    int prefixo = prefixoDeclarado == null ? maximo : prefixoDeclarado;

    if (prefixo < 0 || prefixo > maximo) {
      throw new IllegalArgumentException(
          "prefixo /" + prefixo + " fora da faixa 0.." + maximo + " em '" + limpo + "'");
    }

    // Zera os bits fora do prefixo. Escrever 192.168.1.7/24 e' comum e a
    // intencao e' claramente a rede 192.168.1.0/24; normalizar aqui evita que
    // a comparacao dependa de o administrador ter escrito o endereco de rede
    // exato.
    byte[] normalizada = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      int bitsRestantes = prefixo - (i * 8);
      if (bitsRestantes >= 8) {
        normalizada[i] = bytes[i];
      } else if (bitsRestantes <= 0) {
        normalizada[i] = 0;
      } else {
        int mascara = (0xFF << (8 - bitsRestantes)) & 0xFF;
        normalizada[i] = (byte) (bytes[i] & mascara);
      }
    }

    return new Zona(normalizada, prefixo, limpo);
  }

  /**
   * Converte texto em bytes de endereco SEM consultar DNS.
   *
   * <p>{@link InetAddress#getByName(String)} resolveria nome de maquina, o que
   * significaria uma consulta de rede dentro do caminho de cada requisicao ; e
   * um nome que o atacante controla poderia apontar para dentro de uma zona
   * isenta. Por isso so' se aceita literal.
   */
  private static byte[] interpretarEndereco(String texto, String original) {
    byte[] bytes = analisarLiteral(texto);
    if (bytes == null) {
      throw new IllegalArgumentException(
          "'" + original + "' nao e' um endereco literal em forma canonica "
          + "(nome de maquina e formas abreviadas de IPv4 nao sao aceitos)");
    }
    return bytes;
  }

  /**
   * Interpreta um endereco literal. Devolve os bytes, ou {@code null} quando o
   * texto NAO e' um endereco.
   *
   * <p><b>POR QUE NAO SE USA InetAddress.getByName DIRETO.</b> Um fiscal provou
   * o furo: o reconhecedor anterior aceitava qualquer texto formado por
   * {@code [0-9a-fA-F.:%]}, e existem dominios inteiros dentro desse alfabeto
   * ({@code .ac .ad .ae .af .ba .be .cc .cd .cf .de .ec .ee}). O texto
   * {@code f00dbabe.cafe.ac} passava, e {@code getByName} entao fazia
   * <b>CONSULTA DE DNS</b> ; medida em 187 ms ; dentro do caminho da
   * requisicao. Tres consequencias, todas graves:
   *
   * <ol>
   *   <li>quem controla o dominio escolhia em que zona cair: bastava apontar
   *       o registro A para o endereco do proxy e virar "proxy confiavel";</li>
   *   <li>um servidor autoritativo que simplesmente nao responde prendia uma
   *       thread do Tomcat ; negacao de servico com um cabecalho HTTP;</li>
   *   <li>o javadoc que prometia "nao resolve nome" era falso.</li>
   * </ol>
   *
   * <p>Agora IPv4 e' analisado AQUI, digito a digito, e IPv6 so' e' entregue ao
   * JDK depois de confirmado que contem {@code ':'} ; caractere que nome de
   * maquina nao pode ter, o que torna a consulta de DNS impossivel por
   * construcao, e nao por promessa.
   */
  private static byte[] analisarLiteral(String texto) {
    if (texto == null || texto.isEmpty()) {
      return null;
    }
    if (texto.indexOf(':') >= 0) {
      // IPv6. Nome de maquina nao pode conter ':', entao getByName aqui nunca
      // consulta DNS: ou interpreta como literal, ou lanca.
      try {
        return desmapear(InetAddress.getByName(texto).getAddress());
      } catch (UnknownHostException | IllegalArgumentException e) {
        return null;
      }
    }
    return analisarIpv4(texto);
  }

  /**
   * IPv4 em forma canonica e SO' nela: exatamente quatro grupos decimais de
   * 0 a 255.
   *
   * <p>O JDK aceita formas que ninguem mais aceita, e o fiscal provou o
   * estrago: {@code 10.5} virava {@code 10.0.0.5}, {@code 2130706433} virava
   * {@code 127.0.0.1}, {@code 0000010.0.0.5} era aceito. Alem do casamento
   * inesperado, o motivo gravado na auditoria ("origem 10.5 esta' na zona
   * isenta") nao e' o que nginx, iptables ou o SIEM entendem pelo mesmo texto
   * ; e uma contestacao futura fica impossivel de arbitrar.
   *
   * <p>Zero a' esquerda tambem e' recusado: em varios interpretadores isso e'
   * OCTAL, e {@code 010} vale 8 num e 10 noutro. Divergencia de leitura entre
   * a regra e o firewall e' o comeco de um furo.
   */
  private static byte[] analisarIpv4(String texto) {
    byte[] bytes = new byte[4];
    int grupo = 0;
    int i = 0;
    int n = texto.length();

    while (i < n) {
      int inicio = i;
      int valor = 0;
      while (i < n && texto.charAt(i) >= '0' && texto.charAt(i) <= '9') {
        valor = valor * 10 + (texto.charAt(i) - '0');
        if (valor > 255) {
          return null;
        }
        i++;
      }
      int digitos = i - inicio;
      if (digitos == 0 || digitos > 3) {
        return null;
      }
      // Zero a' esquerda so' e' aceito no proprio zero ("0").
      if (digitos > 1 && texto.charAt(inicio) == '0') {
        return null;
      }
      if (grupo > 3) {
        return null;
      }
      bytes[grupo++] = (byte) valor;

      if (i < n) {
        if (texto.charAt(i) != '.') {
          return null;
        }
        i++;
        if (i == n) {
          return null;
        }
      }
    }
    return grupo == 4 ? bytes : null;
  }

  /**
   * IPv4 mapeado em IPv6 ({@code ::ffff:a.b.c.d}) volta a ser IPv4 de 4 bytes.
   *
   * <p>Sem isto, uma regra escrita como {@code 192.168.1.0/24} nunca casaria
   * com um cliente que chegou pela pilha IPv6 ; a regra existiria, pareceria
   * correta e nao protegeria nada.
   */
  private static byte[] desmapear(byte[] bytes) {
    if (bytes.length != 16) {
      return bytes;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return bytes;
      }
    }
    if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
      return bytes;
    }
    return new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
  }

  /**
   * O endereco pertence a esta faixa?
   *
   * @param enderecoTextual endereco literal do cliente; nulo ou invalido
   *        devolve {@code false}. Quem decide o que fazer com "nao sei de onde
   *        veio" e' o catalogo, nao esta classe ; aqui, nao pertencer e' a
   *        resposta honesta.
   */
  public boolean contem(String enderecoTextual) {
    if (enderecoTextual == null) {
      return false;
    }
    String limpo = enderecoTextual.trim();
    // Zona de escopo de IPv6 (fe80::1%eth0) nao participa da comparacao.
    int porcento = limpo.indexOf('%');
    if (porcento > 0) {
      limpo = limpo.substring(0, porcento);
    }
    byte[] candidato = analisarLiteral(limpo);
    if (candidato == null) {
      return false;
    }

    // Familias diferentes nunca pertencem uma a outra. Comparar IPv4 com IPv6
    // byte a byte casaria por acidente.
    if (candidato.length != rede.length) {
      return false;
    }

    int bitsInteiros = bitsPrefixo / 8;
    for (int i = 0; i < bitsInteiros; i++) {
      if (candidato[i] != rede[i]) {
        return false;
      }
    }
    int bitsSoltos = bitsPrefixo % 8;
    if (bitsSoltos > 0) {
      int mascara = (0xFF << (8 - bitsSoltos)) & 0xFF;
      return (candidato[bitsInteiros] & mascara) == (rede[bitsInteiros] & mascara);
    }
    return true;
  }

  /**
   * O texto e' um endereco literal INTERPRETAVEL?
   *
   * <p>Existe porque {@link OrigemRequisicao} precisa distinguir "entrada da
   * cadeia que e' um endereco" de "entrada que e' lixo". Devolver lixo como se
   * fosse endereco foi um desvio de autenticacao real, pego pelas provas: o
   * catalogo classificaria o lixo como "fora de toda zona protegida" e
   * dispensaria o segundo fator.
   */
  public static boolean enderecoValido(String texto) {
    if (texto == null) {
      return false;
    }
    String limpo = texto.trim();
    int porcento = limpo.indexOf('%');
    if (porcento > 0) {
      limpo = limpo.substring(0, porcento);
    }
    return analisarLiteral(limpo) != null;
  }

  public int getBitsPrefixo() {
    return bitsPrefixo;
  }

  @Override
  public String toString() {
    return textoOriginal.toLowerCase(Locale.ROOT);
  }
}
