package br.pmo.dlp;

/**
 * As tres respostas possiveis para "o que aconteceu com este item?".
 *
 * <p><b>POR QUE TRES, E NAO DUAS.</b> A tentacao de todo relatorio de DLP e'
 * "com achado" contra "sem achado". Isso e' mentira por omissao, porque junta
 * duas coisas opostas na mesma coluna: o documento que foi lido inteiro e nao
 * tinha nada, e o documento que NAO FOI LIDO. Os dois aparecem como "sem
 * achado" e a administracao le "o acervo esta' limpo".
 *
 * <p>{@link #NAO_VARRIDO} e' a categoria que sustenta decisao de orcamento: e'
 * ela que diz quantos documentos deste acervo estao fora do alcance do DLP hoje,
 * e -- pelo motivo agrupado -- se a saida e' mudar uma configuracao ou comprar
 * OCR. Sem esse numero, "vale a pena investir em OCR?" e' pergunta abstrata;
 * com ele, e' planilha.
 *
 * <p>As tres sao uma PARTICAO: todo item cai em exatamente uma. A regra de
 * corte esta' em {@link #de(ResultadoVarredura)}, e a ordem dela importa --
 * "nao varrido" vence "sem achado", porque nao saber e' pior que saber que nao
 * ha'.
 */
public enum CategoriaConformidade {

  /** Lido por inteiro, nada encontrado. E' o unico "limpo" que merece o nome. */
  LIMPO("Limpo"),

  /** Lido por inteiro, encontrou dado pessoal ou sensivel. */
  ACHADO("Com achado"),

  /**
   * NAO foi lido por inteiro. Pode ou nao ter dado sensivel dentro -- ninguem
   * sabe, e e' exatamente esse o ponto.
   */
  NAO_VARRIDO("Nao varrido");

  private final String rotulo;

  CategoriaConformidade(String rotulo) {
    this.rotulo = rotulo;
  }

  /** Nome para tela e relatorio, em portugues. */
  public String getRotulo() {
    return rotulo;
  }

  /**
   * A regra de corte. A ORDEM E' O CONTRATO:
   *
   * <ol>
   *   <li>varredura incompleta -&gt; {@link #NAO_VARRIDO}, TENHA OU NAO achado.
   *       Um item lido pela metade que ja' mostrou um CPF nao e' "com achado":
   *       e' "nao varrido, e o pedaco que deu para ler ja' tinha CPF" -- o que
   *       e' pior, nao melhor. Quem conta esse caso a parte e'
   *       {@link InstantaneoConformidade#getNaoVarridosComAchado()};</li>
   *   <li>completa e com achado -&gt; {@link #ACHADO};</li>
   *   <li>completa e sem achado -&gt; {@link #LIMPO}.</li>
   * </ol>
   *
   * @param resultado o laudo; nulo conta como {@link #NAO_VARRIDO}, porque
   *                  laudo ausente e' o caso mais claro de "nao se sabe"
   */
  public static CategoriaConformidade de(ResultadoVarredura resultado) {
    if (resultado == null || !resultado.isCompleta()) {
      return NAO_VARRIDO;
    }
    return resultado.isLimpo() ? LIMPO : ACHADO;
  }
}
