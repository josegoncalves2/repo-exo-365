package br.pmo.painel;

/**
 * Provas de {@link TextoSubmetido}: o teto de entrada da tela.
 *
 * <p>A asseveracao que importa nao e' "cortou no tamanho certo" -- e' "cortar
 * NAO produz um estado que se pareca com sucesso". Um corte silencioso e' o
 * caminho trivial para passar dado sensivel pela tela.
 */
public final class ProvaTextoSubmetido {

  private ProvaTextoSubmetido() {
  }

  public static void rodar() {
    Prova.secao("TextoSubmetido — dentro do teto");
    TextoSubmetido curto = TextoSubmetido.de("abcde", 10);
    Prova.igual("texto menor que o teto atravessa inteiro", "abcde", curto.getTexto());
    Prova.certo("e nao e' marcado como truncado", !curto.isTruncado());
    Prova.certo("motivo e' nulo quando nao truncou", curto.getMotivo() == null);
    Prova.igual("tamanho original e' o do texto", 5, curto.getTamanhoOriginal());
    Prova.certo("nao esta' vazio", !curto.isVazio());

    Prova.secao("TextoSubmetido — a fronteira exata");
    // Erro de um-a-mais aqui significa cortar um texto que cabia, ou aceitar um
    // que nao cabia. Os dois lados da fronteira sao assevereados.
    TextoSubmetido exato = TextoSubmetido.de("abcde", 5);
    Prova.certo("texto de tamanho IGUAL ao teto nao e' truncado", !exato.isTruncado());
    Prova.igual("e sai inteiro", "abcde", exato.getTexto());
    TextoSubmetido umAMais = TextoSubmetido.de("abcdef", 5);
    Prova.certo("um caractere acima do teto JA' e' truncado", umAMais.isTruncado());
    Prova.igual("e sai com exatamente o teto", "abcde", umAMais.getTexto());

    Prova.secao("TextoSubmetido — truncar nao se disfarca de sucesso");
    TextoSubmetido longo = TextoSubmetido.de("0123456789ABCDE", 10);
    Prova.certo("truncado e' verdadeiro", longo.isTruncado());
    Prova.certo("motivo NAO e' nulo quando truncou", longo.getMotivo() != null);
    Prova.certo("motivo esta' escrito em portugues, nao e' codigo",
                longo.getMotivo() != null && longo.getMotivo().contains("acima do teto"));
    Prova.certo("motivo cita o tamanho original, para o alerta ser julgavel",
                longo.getMotivo() != null && longo.getMotivo().contains("15"));
    Prova.igual("tamanho original preservado apesar do corte", 15, longo.getTamanhoOriginal());
    Prova.igual("o texto analisado tem o tamanho do teto", 10, longo.getTexto().length());

    Prova.secao("TextoSubmetido — caixa em branco NAO e' corte");
    TextoSubmetido nulo = TextoSubmetido.de(null, 10);
    Prova.certo("nulo e' vazio", nulo.isVazio());
    Prova.certo("nulo NAO e' truncado (ausencia de texto nao e' texto cortado)",
                !nulo.isTruncado());
    Prova.igual("nulo vira cadeia vazia, nao nulo", "", nulo.getTexto());
    TextoSubmetido vazio = TextoSubmetido.de("", 10);
    Prova.certo("vazio e' vazio", vazio.isVazio());
    Prova.certo("vazio NAO e' truncado", !vazio.isTruncado());

    Prova.secao("TextoSubmetido — teto invalido falha alto");
    // Teto zero truncaria TUDO para nada, e a tela responderia "nao varrido" a
    // qualquer entrada sem ninguem entender por que. Tem de recusar na partida.
    Prova.recusa("teto zero e' recusado", () -> TextoSubmetido.de("abc", 0));
    Prova.recusa("teto negativo e' recusado", () -> TextoSubmetido.de("abc", -1));
  }
}
