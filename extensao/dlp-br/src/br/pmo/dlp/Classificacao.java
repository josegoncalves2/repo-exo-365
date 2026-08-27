package br.pmo.dlp;

import java.util.List;
import java.util.Locale;

import br.pmo.dlp.RegrasSensiveis.Severidade;

/**
 * Rotulo de sigilo de um documento, derivado do que o motor achou dentro dele.
 *
 * <p><b>POR QUE CLASSIFICAR AUTOMATICAMENTE.</b> O briefing pede "classificacao
 * de dados". Classificacao manual, em orgao publico, nao acontece: ninguem
 * marca planilha por planilha, e o campo fica vazio ou fica errado. Derivar do
 * conteudo e' o unico jeito de a classificacao existir para o acervo inteiro no
 * primeiro dia. O rotulo automatico e' PISO, nunca teto -- a administracao pode
 * elevar um documento a mao, jamais rebaixar por engano automatico.
 *
 * <p><b>A ESCALA.</b> Quatro niveis, alinhados ao que a Lei 12.527/2011 (LAI) e
 * a Lei 13.709/2018 (LGPD) exigem distinguir na pratica:
 *
 * <table border="1">
 *   <tr><th>Nivel</th><th>Gatilho</th><th>Efeito pratico</th></tr>
 *   <tr><td>PUBLICO</td><td>nenhum achado</td><td>circula livre</td></tr>
 *   <tr><td>INTERNO</td><td>so' achados BAIXA (e-mail, telefone, CEP)</td>
 *       <td>registra, nao bloqueia</td></tr>
 *   <tr><td>RESTRITO</td><td>algum achado MEDIA (chave PIX, segredo)</td>
 *       <td>alerta o autor e o administrador</td></tr>
 *   <tr><td>SIGILOSO</td><td>algum achado ALTA (CPF, CNPJ, cartao, CNH...)</td>
 *       <td>candidato a bloqueio ou quarentena</td></tr>
 * </table>
 *
 * <p><b>POR QUE E-MAIL E TELEFONE NAO SOBEM SOZINHOS PARA SIGILOSO.</b> Estao
 * no rodape de todo oficio, de toda ata e de toda assinatura. Trata-los como
 * SIGILOSO classificaria o acervo inteiro como sigiloso -- e uma classificacao
 * que vale para tudo nao distingue nada, entao a administracao para de olhar.
 * Eles sobem para RESTRITO por VOLUME, que e' o sinal real: um e-mail e'
 * assinatura, quatrocentos e-mails sao uma lista de contatos vazando.
 */
public enum Classificacao {

  PUBLICO(0),
  INTERNO(1),
  RESTRITO(2),
  SIGILOSO(3);

  /**
   * A partir de quantas ocorrencias BAIXA o documento deixa de ser assinatura e
   * passa a ser cadastro. Cinquenta: um oficio com anexos raramente passa de
   * uma duzia de contatos; uma exportacao de lista comeca em centenas.
   */
  public static final int VOLUME_QUE_ELEVA = 50;

  private final int ordem;

  Classificacao(int ordem) {
    this.ordem = ordem;
  }

  public int getOrdem() {
    return ordem;
  }

  /** Verdadeiro se este nivel e' igual ou mais restritivo que o outro. */
  public boolean alcanca(Classificacao outra) {
    return outra != null && this.ordem >= outra.ordem;
  }

  /**
   * Deriva a classificacao dos achados.
   *
   * @param achados o que o motor encontrou; nulo ou vazio significa PUBLICO
   */
  public static Classificacao derivar(List<Achado> achados) {
    if (achados == null || achados.isEmpty()) {
      return PUBLICO;
    }
    Severidade maior = null;
    int ocorrenciasBaixa = 0;
    for (Achado achado : achados) {
      Severidade s = achado.getSeveridade();
      if (maior == null || s.compareTo(maior) > 0) {
        maior = s;
      }
      if (s == Severidade.BAIXA) {
        ocorrenciasBaixa += achado.getQuantidade();
      }
    }
    if (maior == Severidade.ALTA) {
      return SIGILOSO;
    }
    if (maior == Severidade.MEDIA) {
      return RESTRITO;
    }
    // So' sobrou BAIXA: o volume e' que decide.
    return ocorrenciasBaixa >= VOLUME_QUE_ELEVA ? RESTRITO : INTERNO;
  }

  /** Le de configuracao, com padrao seguro quando o texto nao e' reconhecido. */
  public static Classificacao de(String texto, Classificacao padrao) {
    if (texto == null || texto.trim().isEmpty()) {
      return padrao;
    }
    try {
      return valueOf(texto.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return padrao;
    }
  }
}
