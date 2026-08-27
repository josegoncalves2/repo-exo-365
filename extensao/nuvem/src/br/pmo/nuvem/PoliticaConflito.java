package br.pmo.nuvem;

/**
 * Politica de conflito na sincronizacao entre o drive remoto e o JCR local.
 *
 * <p><b>O PROBLEMA.</b> Sincronizacao de arquivo em orgao publico tem um
 * imperativo que vem antes de qualquer algoritmo: <b>nunca perder dado em
 * silencio</b>. Um contracheque editado localmente enquanto o original mudou no
 * servidor nao pode simplesmente "vencer" a favor de um dos lados sem registro.
 *
 * <p><b>A DECISAO.</b> Quando os DOIS lados mudaram desde a ultima
 * sincronizacao, a politica NAO escolhe um vencedor: devolve
 * {@link Veredito#CONFLITO}, e quem sincroniza grava uma copia local com
 * sufixo de data e registra o conflito para decisao humana. Perder dado por
 * algoritmo e' aceitavel em ferramenta de produtividade; em portal de orgao
 * publico, e' processo trabalhista em potencial.
 *
 * <p>Quando so' UM lado mudou, o vencedor e' o que mudou -- e isso e'
 * seguro, porque o outro lado nao tocou no arquivo.
 *
 * <p><b>ETAG NULO.</b> Servidor que nao informa etag nao pode afirmar
 * "nunca mudou". Nesse caso o criterio cai para tamanho+data de modificacao,
 * e a ausencia de etag e' registrada no veredito ({@code confiavelEtag=false})
 * para quem chama decidir se quer endurecer.
 */
public final class PoliticaConflito {

  /** O que fazer com o arquivo local ao final da comparacao. */
  public enum Veredito {

    /** Local e remoto identicos: nada a fazer. */
    INALTERADO,

    /** So' o remoto mudou: o arquivo local pode ser substituido. */
    REMOTO_VENCE,

    /** So' o local mudou: enviar ao servidor. */
    LOCAL_VENCE,

    /**
     * Os dois mudaram: NAO sobrescrever. Gravar copia com sufixo e registrar
     * para decisao humana. E' o unico veredito que o operador pediu que nunca
     * fosse silencioso.
     */
    CONFLITO
  }

  private PoliticaConflito() {
  }

  /**
   * Decide o veredito comparando o que sabemos do lado local e do remoto.
   *
   * @param etagLocal            etag da ultima sincronizacao (pode ser nulo)
   * @param etagRemoto           etag atual no servidor (pode ser nulo)
   * @param localMudou           verdadeiro se o arquivo local foi alterado desde
   *                             a ultima sincronizacao
   * @param remotoMudou          verdadeiro se o remoto mudou desde a ultima
   *                             sincronizacao (etag diferente, ou tamanho/data
   *                             diferente quando nao ha' etag)
   * @param remotoConfiavelEtag  sai preenchido: falso quando o servidor nao
   *                             informou etag (criterio secundario em uso)
   */
  public static Veredito decidir(String etagLocal,
                                 String etagRemoto,
                                 boolean localMudou,
                                 boolean remotoMudou,
                                 boolean[] remotoConfiavelEtag) {
    boolean etagConfiavel = etagRemoto != null && !etagRemoto.isEmpty();
    if (remotoConfiavelEtag != null && remotoConfiavelEtag.length > 0) {
      remotoConfiavelEtag[0] = etagConfiavel;
    }
    if (!localMudou && !remotoMudou) {
      return Veredito.INALTERADO;
    }
    if (localMudou && remotoMudou) {
      return Veredito.CONFLITO;
    }
    return remotoMudou ? Veredito.REMOTO_VENCE : Veredito.LOCAL_VENCE;
  }

  /**
   * O sufixo de conflito para a copia preservada localmente. Usa a data de
   * hoje em UTC -- nunca o conteudo do arquivo, para o nome nao vazar dado.
   */
  public static String sufixoConflito(long agora) {
    java.text.SimpleDateFormat fmt =
        new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return "-conflito-" + fmt.format(new java.util.Date(agora));
  }
}
