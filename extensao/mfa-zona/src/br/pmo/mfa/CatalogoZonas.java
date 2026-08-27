package br.pmo.mfa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A politica de zonas: dado um endereco, exigir ou nao o segundo fator.
 *
 * <h2>Precedencia, e por que a isencao vence</h2>
 *
 * Uma faixa pode estar nas duas listas ; por sobreposicao ({@code 10.0.0.0/8}
 * exige, {@code 10.1.2.0/24} isenta) ou por engano. A isencao vence SEMPRE, e
 * nao por comodidade: isencao e' sempre um ato administrativo deliberado e
 * especifico, enquanto exigencia costuma ser escrita em faixa larga. Se a
 * exigencia vencesse, a unica forma de isentar uma sub-rede seria reescrever a
 * faixa larga em pedacos ; e a primeira vez que alguem errasse essa aritmetica,
 * trancaria gente do lado de fora sem perceber.
 *
 * <h2>Estado inerte por padrao</h2>
 *
 * Lista de exigencia VAZIA significa "esta funcionalidade nao opina". Nao
 * significa "exija de todos" nem "isente todos": significa que a decisao
 * continua inteiramente com o mecanismo por grupo do add-on, como era antes
 * desta extensao existir. Ligar exigencia global sem alguem ter cadastrado o
 * segundo fator TRANCA o portal, e quem tem a chave para reverter e' justamente
 * quem ficou de fora.
 *
 * <h2>Origem indeterminada</h2>
 *
 * Quando nao se consegue estabelecer de onde veio a requisicao, a resposta
 * padrao e' EXIGIR. E' a direcao segura: exigir demais atrapalha e e'
 * reversivel; isentar por engano e' um furo silencioso. E o custo do erro e'
 * baixo porque a tela de segundo fator do add-on tambem e' onde o usuario
 * cadastra o dele ; ninguem fica sem caminho de volta.
 *
 * <p>Ainda assim e' configuravel, porque so' o administrador conhece a
 * topologia dele.
 */
public final class CatalogoZonas {

  /** O que fazer com uma requisicao cuja origem nao se conseguiu determinar. */
  public enum QuandoIndeterminado {
    EXIGIR, ISENTAR;

    public static QuandoIndeterminado de(String texto, QuandoIndeterminado padrao) {
      if (texto == null || texto.trim().isEmpty()) {
        return padrao;
      }
      try {
        return valueOf(texto.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // Configuracao errada nao pode virar isencao silenciosa.
        return padrao;
      }
    }
  }

  /** A decisao, com o porque em portugues ; vai para o log de auditoria. */
  public static final class Decisao {
    private final boolean exigeSegundoFator;
    private final String motivo;

    Decisao(boolean exigeSegundoFator, String motivo) {
      this.exigeSegundoFator = exigeSegundoFator;
      this.motivo = motivo;
    }

    public boolean exigeSegundoFator() {
      return exigeSegundoFator;
    }

    public String getMotivo() {
      return motivo;
    }

    @Override
    public String toString() {
      return (exigeSegundoFator ? "EXIGE" : "ISENTA") + ": " + motivo;
    }
  }

  private final List<Zona> exigir;
  private final List<Zona> isentar;
  private final QuandoIndeterminado quandoIndeterminado;

  public CatalogoZonas(List<Zona> exigir,
                       List<Zona> isentar,
                       QuandoIndeterminado quandoIndeterminado) {
    this.exigir = imutavel(exigir);
    this.isentar = imutavel(isentar);
    this.quandoIndeterminado =
        quandoIndeterminado == null ? QuandoIndeterminado.EXIGIR : quandoIndeterminado;
  }

  private static List<Zona> imutavel(List<Zona> origem) {
    List<Zona> copia = new ArrayList<>();
    if (origem != null) {
      for (Zona zona : origem) {
        if (zona != null) {
          copia.add(zona);
        }
      }
    }
    return Collections.unmodifiableList(copia);
  }

  /**
   * Interpreta uma lista separada por virgula.
   *
   * <p>Faixa invalida NAO e' ignorada em silencio: lanca. Regra de seguranca
   * escrita errada tem de aparecer no boot. Ignorar produziria uma politica que
   * o administrador acredita ter e que nao existe.
   */
  public static List<Zona> interpretarLista(String csv) {
    List<Zona> zonas = new ArrayList<>();
    if (csv == null || csv.trim().isEmpty()) {
      return zonas;
    }
    for (String pedaco : csv.split(",")) {
      String limpo = pedaco.trim();
      if (!limpo.isEmpty()) {
        zonas.add(Zona.de(limpo));
      }
    }
    return zonas;
  }

  /** Sem faixa de exigencia, a extensao nao opina sobre nada. */
  public boolean estaInerte() {
    return exigir.isEmpty();
  }

  public List<Zona> getExigir() {
    return exigir;
  }

  public List<Zona> getIsentar() {
    return isentar;
  }

  public QuandoIndeterminado getQuandoIndeterminado() {
    return quandoIndeterminado;
  }

  /**
   * @param endereco endereco ja' resolvido por {@link OrigemRequisicao}; nulo
   *        significa origem indeterminada
   */
  public Decisao decidir(String endereco) {
    if (estaInerte()) {
      return new Decisao(false, "nenhuma zona de exigencia configurada; extensao inerte");
    }

    if (endereco == null || endereco.trim().isEmpty()) {
      boolean exige = quandoIndeterminado == QuandoIndeterminado.EXIGIR;
      return new Decisao(exige,
          "origem indeterminada; politica para indeterminado e' " + quandoIndeterminado);
    }

    for (Zona zona : isentar) {
      if (zona.contem(endereco)) {
        return new Decisao(false, "origem " + endereco + " esta' na zona isenta " + zona);
      }
    }

    for (Zona zona : exigir) {
      if (zona.contem(endereco)) {
        return new Decisao(true, "origem " + endereco + " esta' na zona protegida " + zona);
      }
    }

    return new Decisao(false, "origem " + endereco + " nao esta' em nenhuma zona protegida");
  }
}
