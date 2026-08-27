package br.pmo.dlp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Prova do relatorio de conformidade.
 *
 * <p>O que estas asseveracoes protegem, em uma frase: <b>um relatorio de
 * conformidade errado e' pior que relatorio nenhum</b>, porque produz decisao
 * com aparencia de dado. Por isso se prova a particao das categorias, a
 * separacao entre itens e ocorrencias, a contagem sob concorrencia, e a
 * classificacao dos motivos REAIS que o adaptador escreve hoje.
 */
final class ProvaRelatorio {

  private ProvaRelatorio() {
  }

  static void rodar() {
    categoriasParticionam();
    motivosReaisCaemNaGavetaCerta();
    itensNaoSaoOcorrencias();
    derivaDeMotivoFicaBarulhenta();
    contagemSobreviveAConcorrencia();
    csvNaoExecutaFormula();
    saidaLegivel();
    falhaDeVarreduraNaoSome();
  }

  /**
   * O sumidouro que a integracao do ConectorDlpRegex ainda tinha: item que
   * estoura excecao no meio da varredura nao passava por gaveta nenhuma.
   */
  private static void falhaDeVarreduraNaoSome() {
    Prova.secao("Relatorio — item que fez o DLP falhar tem de aparecer, nao sumir");

    RelatorioConformidade relatorio = new RelatorioConformidade("com falha");
    relatorio.registrar("ok.txt", new Varredura().varrer("Ata comum."));
    // E' assim que o bloco catch registra: sem laudo, com o motivo ja' sabido.
    relatorio.registrar("quebrou.pdf", null, MotivoNaoVarrido.FALHA_NA_VARREDURA);
    InstantaneoConformidade foto = relatorio.instantaneo();

    Prova.igual("as duas varreduras contam", 2, foto.getTotal());
    Prova.igual("a que falhou entra como NAO VARRIDO", 1,
                foto.getQuantidade(CategoriaConformidade.NAO_VARRIDO));
    Prova.igual("na gaveta de FALHA, nao na de digitalizacao", 1,
                foto.getQuantidade(MotivoNaoVarrido.FALHA_NA_VARREDURA));
    Prova.igual("e a gaveta de OCR fica ZERADA — senao o bug viraria pedido de orcamento",
                0, foto.getQuantidade(MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO));
    Prova.certo("o encaminhamento manda ler o log, nao comprar nada",
                MotivoNaoVarrido.FALHA_NA_VARREDURA.getEncaminhamento().contains("log"));
    Prova.certo("a referencia do item quebrado esta' na amostra",
                foto.getAmostras(MotivoNaoVarrido.FALHA_NA_VARREDURA).contains("quebrou.pdf"));

    Prova.certo("registrar(ref, null) sem motivo ainda conta, em OUTRO",
                contarComLaudoNulo() == 1);

    Prova.secao("Relatorio — o numero e' de VARREDURAS, e o relatorio diz isso");
    RelatorioConformidade repetido = new RelatorioConformidade("repeticao");
    ResultadoVarredura mesmo = new Varredura().varrer("Ata comum.");
    repetido.registrar("mesmo-arquivo.txt", mesmo);
    repetido.registrar("mesmo-arquivo.txt", mesmo);
    repetido.registrar("mesmo-arquivo.txt", mesmo);
    InstantaneoConformidade tres = repetido.instantaneo();
    Prova.igual("o mesmo documento tres vezes conta tres", 3, tres.getTotal());
    Prova.certo("e o texto avisa que sao varreduras, nao documentos distintos",
                tres.emTexto().contains("nao documentos distintos"));
  }

  private static int contarComLaudoNulo() {
    RelatorioConformidade r = new RelatorioConformidade("nulo");
    r.registrar("x", null);
    return r.instantaneo().getQuantidade(MotivoNaoVarrido.OUTRO);
  }

  private static void categoriasParticionam() {
    Prova.secao("Relatorio — as tres categorias sao uma particao, e incompleta vence");

    Varredura motor = new Varredura();
    ResultadoVarredura limpo = motor.varrer("Ata de reuniao ordinaria, sem anexos.");
    ResultadoVarredura achado = motor.varrer("CPF " + ProvaRegras.CPF_VALIDO_1);
    ResultadoVarredura naoVarrido = motor.varrerParcial(
        "ficha.pdf\n", "nenhum extrator leu o binario: provavel digitalizacao, exige OCR");
    ResultadoVarredura parcialComAchado = motor.varrerParcial(
        "CPF " + ProvaRegras.CPF_VALIDO_2, "extracao parcial do PDF");

    Prova.igual("laudo limpo e completo -> LIMPO",
                CategoriaConformidade.LIMPO, CategoriaConformidade.de(limpo));
    Prova.igual("laudo com achado e completo -> ACHADO",
                CategoriaConformidade.ACHADO, CategoriaConformidade.de(achado));
    Prova.igual("laudo incompleto -> NAO_VARRIDO",
                CategoriaConformidade.NAO_VARRIDO, CategoriaConformidade.de(naoVarrido));
    Prova.igual("laudo INCOMPLETO E COM ACHADO -> NAO_VARRIDO (nao ACHADO)",
                CategoriaConformidade.NAO_VARRIDO, CategoriaConformidade.de(parcialComAchado));
    Prova.igual("laudo ausente -> NAO_VARRIDO",
                CategoriaConformidade.NAO_VARRIDO, CategoriaConformidade.de(null));

    RelatorioConformidade relatorio = new RelatorioConformidade("Prova");
    relatorio.registrar("a", limpo);
    relatorio.registrar("b", achado);
    relatorio.registrar("c", naoVarrido);
    relatorio.registrar("d", parcialComAchado);
    InstantaneoConformidade foto = relatorio.instantaneo();

    Prova.igual("total confere", 4, foto.getTotal());
    Prova.igual("1 limpo", 1, foto.getQuantidade(CategoriaConformidade.LIMPO));
    Prova.igual("1 com achado", 1, foto.getQuantidade(CategoriaConformidade.ACHADO));
    Prova.igual("2 nao varridos", 2, foto.getQuantidade(CategoriaConformidade.NAO_VARRIDO));
    Prova.certo("as tres categorias somam o total",
                foto.getQuantidade(CategoriaConformidade.LIMPO)
                + foto.getQuantidade(CategoriaConformidade.ACHADO)
                + foto.getQuantidade(CategoriaConformidade.NAO_VARRIDO) == foto.getTotal());
    Prova.igual("o balde mais grave tem numero proprio: 1 nao varrido COM achado",
                1, foto.getNaoVarridosComAchado());
    Prova.igual("percentual de nao varridos", "50.00",
                String.format(java.util.Locale.ROOT, "%.2f",
                              foto.getPercentual(CategoriaConformidade.NAO_VARRIDO)));
  }

  /**
   * Os textos abaixo sao os que o ConectorDlpRegex e os extratores escrevem HOJE.
   * Se alguem reescrever uma dessas mensagens, esta prova quebra -- que e'
   * exatamente o ponto: a deriva tem de parar o build, nao passar despercebida.
   */
  private static void motivosReaisCaemNaGavetaCerta() {
    Prova.secao("Relatorio — os motivos REAIS do adaptador caem na gaveta certa");

    Prova.igual("acima do teto de bytes -> decisao de configuracao",
                MotivoNaoVarrido.ACIMA_DO_TETO_DE_BYTES,
                MotivoNaoVarrido.classificar(
                    "arquivo de 41943040 bytes acima do teto de 16777216; varrido so' por nome e titulo"));
    Prova.igual("nenhum extrator leu -> decisao de investir em OCR",
                MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO,
                MotivoNaoVarrido.classificar(
                    "nenhum extrator leu o binario: provavel digitalizacao, exige OCR"));
    Prova.igual("sem conteudo binario",
                MotivoNaoVarrido.SEM_CONTEUDO_BINARIO,
                MotivoNaoVarrido.classificar("item sem conteudo binario associado"));
    Prova.igual("sem propriedade de dados binarios",
                MotivoNaoVarrido.SEM_CONTEUDO_BINARIO,
                MotivoNaoVarrido.classificar("item sem propriedade de dados binarios"));
    Prova.igual("teto de caracteres do motor",
                MotivoNaoVarrido.ACIMA_DO_TETO_DE_CARACTERES,
                MotivoNaoVarrido.classificar(
                    "documento maior que o teto de 2000000 caracteres; varridos os primeiros 2000000"));
    Prova.igual("orcamento de tempo do motor",
                MotivoNaoVarrido.ORCAMENTO_DE_TEMPO_ESGOTADO,
                MotivoNaoVarrido.classificar(
                    "orcamento de 10000 ms esgotado apos 10412 ms; regras restantes nao foram aplicadas"));
    Prova.igual("bomba de descompressao -> investigar, nao configurar",
                MotivoNaoVarrido.RECUSADO_POR_SEGURANCA,
                MotivoNaoVarrido.classificar(
                    "arquivo recusado por limite de seguranca (possivel bomba de descompressao) em x.docx"));
    Prova.igual("formato corrompido ou cifrado",
                MotivoNaoVarrido.FORMATO_NAO_SUPORTADO,
                MotivoNaoVarrido.classificar(
                    "formato nao suportado ou arquivo corrompido/cifrado em contrato.pdf"));
    Prova.igual("imagem sem motor de OCR -> investir em OCR",
                MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO,
                MotivoNaoVarrido.classificar(
                    "motor de OCR nao configurado (exo.dlp.ocr.comando vazio): a imagem NAO foi lida"
                    + " e NAO pode ser considerada livre de dados sensiveis. Exige inspecao OCR."));

    Prova.igual("OCR que ESTOUROU O TEMPO nao vira 'comprar OCR' — o OCR ja' existe",
                MotivoNaoVarrido.ORCAMENTO_DE_TEMPO_ESGOTADO,
                MotivoNaoVarrido.classificar(
                    "OCR passou do teto de 30000 ms em digitalizacao.png e foi encerrado:"
                    + " a imagem NAO foi lida."));

    Prova.igual("motivo desconhecido cai em OUTRO", MotivoNaoVarrido.OUTRO,
                MotivoNaoVarrido.classificar("aconteceu alguma coisa estranha"));
    Prova.igual("motivo vazio cai em OUTRO", MotivoNaoVarrido.OUTRO,
                MotivoNaoVarrido.classificar(null));

    Prova.certo("cada gaveta traz o encaminhamento escrito",
                MotivoNaoVarrido.PROVAVEL_DIGITALIZACAO.getEncaminhamento().contains("OCR")
                && MotivoNaoVarrido.ACIMA_DO_TETO_DE_BYTES.getEncaminhamento().contains("configuracao")
                && MotivoNaoVarrido.RECUSADO_POR_SEGURANCA.getEncaminhamento().contains("investigar"));
  }

  private static void itensNaoSaoOcorrencias() {
    Prova.secao("Relatorio — 300 CPFs em 1 arquivo nao e' 1 CPF em 300 arquivos");

    Varredura motor = new Varredura();
    StringBuilder contracheque = new StringBuilder();
    String[] cpfs = { "111.444.777-35", "529.982.247-25", "360.848.529-55" };
    for (int i = 0; i < 30; i++) {
      contracheque.append("servidor ").append(i).append(": ").append(cpfs[i % 3]).append('\n');
    }

    RelatorioConformidade umArquivoCheio = new RelatorioConformidade("um arquivo");
    umArquivoCheio.registrar("contracheque.csv", motor.varrer(contracheque.toString()));
    InstantaneoConformidade a = umArquivoCheio.instantaneo();

    RelatorioConformidade muitosArquivos = new RelatorioConformidade("muitos arquivos");
    for (int i = 0; i < 30; i++) {
      muitosArquivos.registrar("oficio-" + i + ".txt",
                               motor.varrer("Requerente CPF " + cpfs[i % 3]));
    }
    InstantaneoConformidade b = muitosArquivos.instantaneo();

    Prova.igual("um arquivo com 30 CPFs: 1 ITEM", 1, a.getItensPorRotulo().get("CPF").intValue());
    Prova.igual("um arquivo com 30 CPFs: 30 OCORRENCIAS",
                30L, a.getOcorrenciasPorRotulo().get("CPF").longValue());
    Prova.igual("30 arquivos com 1 CPF: 30 ITENS", 30, b.getItensPorRotulo().get("CPF").intValue());
    Prova.igual("30 arquivos com 1 CPF: 30 OCORRENCIAS",
                30L, b.getOcorrenciasPorRotulo().get("CPF").longValue());
    Prova.certo("as ocorrencias coincidem, os itens NAO — e e' isso que separa"
                + " 'tratar um arquivo' de 'treinar o orgao'",
                a.getOcorrenciasPorRotulo().get("CPF").equals(b.getOcorrenciasPorRotulo().get("CPF"))
                && !a.getItensPorRotulo().get("CPF").equals(b.getItensPorRotulo().get("CPF")));
  }

  private static void derivaDeMotivoFicaBarulhenta() {
    Prova.secao("Relatorio — motivo nao reconhecido aparece CRU, para a deriva ser vista");

    RelatorioConformidade relatorio = new RelatorioConformidade("deriva");
    Varredura motor = new Varredura();
    relatorio.registrar("x", motor.varrerParcial("x", "redacao nova que ninguem avisou"));
    InstantaneoConformidade foto = relatorio.instantaneo();

    Prova.igual("contou em OUTRO", 1, foto.getQuantidade(MotivoNaoVarrido.OUTRO));
    Prova.certo("e guardou o texto cru",
                foto.getAmostrasDeMotivoCru().contains("redacao nova que ninguem avisou"));
    Prova.certo("que aparece no relatorio em texto",
                foto.emTexto().contains("redacao nova que ninguem avisou")
                && foto.emTexto().contains("NAO reconhecidos"));
  }

  /**
   * DlpOperationProcessorImpl tem pool de threads. Um {@code int++} sem trava
   * perde incrementos, e o relatorio mente PARA MENOS.
   */
  private static void contagemSobreviveAConcorrencia() {
    Prova.secao("Relatorio — contagem nao se perde sob concorrencia");

    final int threads = 8;
    final int porThread = 500;
    final RelatorioConformidade relatorio = new RelatorioConformidade("concorrencia");
    final Varredura motor = new Varredura();
    final ResultadoVarredura comAchado = motor.varrer("CPF " + ProvaRegras.CPF_VALIDO_1);
    final CountDownLatch largada = new CountDownLatch(1);
    final CountDownLatch chegada = new CountDownLatch(threads);

    List<Thread> corredores = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread corredor = new Thread(() -> {
        try {
          largada.await();
          for (int i = 0; i < porThread; i++) {
            relatorio.registrar("item", comAchado);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          chegada.countDown();
        }
      });
      corredores.add(corredor);
      corredor.start();
    }
    largada.countDown();
    try {
      chegada.await();
      for (Thread corredor : corredores) {
        corredor.join();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    InstantaneoConformidade foto = relatorio.instantaneo();
    int esperado = threads * porThread;
    Prova.igual(threads + " threads x " + porThread + " registros: nenhum perdido",
                esperado, foto.getTotal());
    Prova.igual("categoria ACHADO tambem intacta",
                esperado, foto.getQuantidade(CategoriaConformidade.ACHADO));
    Prova.igual("itens por rotulo intactos", esperado, foto.getItensPorRotulo().get("CPF").intValue());
    Prova.igual("ocorrencias por rotulo intactas",
                (long) esperado, foto.getOcorrenciasPorRotulo().get("CPF").longValue());
    Prova.igual("e as amostras ficaram limitadas ao teto",
                RelatorioConformidade.MAX_AMOSTRAS,
                foto.getAmostras(CategoriaConformidade.ACHADO).size());
  }

  /** Planilha executa formula. Relatorio de conformidade e' aberto em planilha. */
  private static void csvNaoExecutaFormula() {
    Prova.secao("Relatorio — CSV nao entrega formula para a planilha executar");

    Prova.igual("campo que comeca com = e' neutralizado",
                "'=cmd|' /c calc'!A1", InstantaneoConformidade.campo("=cmd|' /c calc'!A1"));
    Prova.igual("campo que comeca com + tambem", "'+1+1",
                InstantaneoConformidade.campo("+1+1"));
    Prova.igual("campo que comeca com @ tambem", "'@SUM(A1)",
                InstantaneoConformidade.campo("@SUM(A1)"));
    Prova.igual("campo que comeca com - tambem", "'-2+3",
                InstantaneoConformidade.campo("-2+3"));
    Prova.igual("ponto-e-virgula e' escapado para nao quebrar a coluna",
                "\"a;b\"", InstantaneoConformidade.campo("a;b"));
    Prova.igual("aspas sao dobradas", "\"a\"\"b\"", InstantaneoConformidade.campo("a\"b"));
    Prova.igual("quebra de linha vira espaco", "a b", InstantaneoConformidade.campo("a\nb"));
    Prova.igual("texto comum passa intacto", "CPF", InstantaneoConformidade.campo("CPF"));

    RelatorioConformidade relatorio = new RelatorioConformidade("csv");
    relatorio.registrar("x", new Varredura().varrerParcial("x", "=SOMA(A1:A9)"));
    String csv = relatorio.instantaneo().emCsv();
    Prova.certo("o motivo cru malicioso sai neutralizado no CSV", csv.contains("'=SOMA(A1:A9)"));
    Prova.certo("o cabecalho esta' la'", csv.startsWith("secao;chave;quantidade;percentual;observacao"));
  }

  private static void saidaLegivel() {
    Prova.secao("Relatorio — a saida em texto diz o que decide");

    Varredura motor = new Varredura();
    RelatorioConformidade relatorio = new RelatorioConformidade("Acervo — ciclo de prova");
    relatorio.registrar("ata-01.txt", motor.varrer("Ata sem dado pessoal."));
    relatorio.registrar("oficio-02.txt", motor.varrer("CPF " + ProvaRegras.CPF_VALIDO_1));
    relatorio.registrar("digitalizado-03.pdf", motor.varrerParcial(
        "digitalizado-03.pdf\n", "nenhum extrator leu o binario: provavel digitalizacao, exige OCR"));
    relatorio.registrar("enorme-04.zip", motor.varrerParcial(
        "enorme-04.zip\n", "arquivo de 41943040 bytes acima do teto de 16777216; varrido so' por nome e titulo"));

    String texto = relatorio.instantaneo().emTexto();
    System.out.println(texto);

    Prova.certo("mostra as tres situacoes", texto.contains("Limpo")
                && texto.contains("Com achado") && texto.contains("Nao varrido"));
    Prova.certo("separa 'exige OCR' de 'revisar configuracao'",
                texto.contains("exige OCR") && texto.contains("revisar configuracao"));
    Prova.certo("declara que nao carrega valor detectado",
                texto.contains("NENHUM VALOR DETECTADO"));
    Prova.certo("nenhum CPF aparece no relatorio",
                !texto.contains("111.444.777-35") && !texto.contains("11144477735"));

    // O defeito que a leitura da propria saida pegou: item nao varrido recebe
    // Classificacao.PUBLICO do motor (nada achado no pedaco lido), e somar isso
    // publicaria "3 documentos PUBLICOS" com dois deles nunca abertos.
    InstantaneoConformidade foto = relatorio.instantaneo();
    Prova.igual("so' o item LIMPO conta como PUBLICO — os 2 nao varridos NAO",
                1, foto.getQuantidade(Classificacao.PUBLICO));
    Prova.igual("o item com CPF conta como SIGILOSO",
                1, foto.getQuantidade(Classificacao.SIGILOSO));
    Prova.certo("a soma das classificacoes e' o total MENOS os nao varridos",
                foto.getQuantidade(Classificacao.PUBLICO)
                + foto.getQuantidade(Classificacao.INTERNO)
                + foto.getQuantidade(Classificacao.RESTRITO)
                + foto.getQuantidade(Classificacao.SIGILOSO)
                == foto.getTotal() - foto.getQuantidade(CategoriaConformidade.NAO_VARRIDO));
    Prova.certo("e o relatorio DIZ que nao varrido nao tem classificacao",
                texto.contains("nao tem classificacao: tem pendencia"));
  }
}
