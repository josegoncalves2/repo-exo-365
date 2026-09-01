# =====================================================================
# DLP eXo — motor de protecao contra vazamento de dados
#
# POR QUE python:slim E NAO alpine: o OCR (tesseract) e os extratores de PDF
# sao muito mais previsiveis em glibc. Alpine economizaria ~40 MiB e custaria
# depuracao de musl em algo que precisa ser confiavel.
#
# O servico NAO roda como root: le arquivo de origem externa o dia inteiro.
# =====================================================================
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DLP_DADOS=/dados

# tesseract   -> inspecao OCR (imagem e PDF digitalizado)
# poppler     -> pdftotext, extracao de PDF com camada de texto
# libmagic    -> reforca a deteccao de tipo real do arquivo
# p7zip-full  -> DOIS usos, e os dois importam:
#                1. extracao de .7z e .rar para inspecao -- compactador e' o
#                   esconderijo mais obvio, e parar no .zip deixava a porta
#                   ao lado aberta;
#                2. VERIFICACAO INDEPENDENTE do ZIP AES-256 que a acao
#                   CRIPTOGRAFAR produz. A suite de testes cifra com o nosso
#                   codigo e decifra com o 7z -- se so' o nosso codigo
#                   conseguisse abrir, o arquivo seria inutil para quem
#                   recebe, e o teste nao teria provado nada.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-por tesseract-ocr-eng \
        poppler-utils libmagic1 p7zip-full curl; \
    rm -rf /var/lib/apt/lists/*

# DEPENDENCIAS PYTHON — todas OBRIGATORIAS, sem "|| echo AVISO".
#
# A versao anterior tolerava a falha da instalacao e seguia com um motor mais
# fraco. Num inspetor de conteudo isso e' pior do que nao subir: a instalacao
# ficaria lendo menos formatos e ninguem saberia, porque o unico sinal era uma
# linha de log do build. Aqui, ou o motor tem tudo de que precisa, ou a imagem
# nao existe.
#
#   cryptography -> AES-256-GCM do cofre, ZIP AES-256 e S/MIME. E' a UNICA
#                   dependencia de terceiro do caminho de decisao, e a razao
#                   esta' no cabecalho de motor/cofre.py: AES escrito a mao e'
#                   pior que AES auditado.
#   pdfminer.six -> PDF com layout; sem ele, tabela de PDF vira sopa de letras
#                   e o casamento por contexto perde a palavra vizinha.
#   python-magic -> confirma o tipo REAL pelo conteudo.
RUN pip install --no-cache-dir \
        "cryptography==50.0.1" \
        "pdfminer.six==20240706" \
        "python-magic==0.4.27"

RUN groupadd -g 1000 dlp && useradd -u 1000 -g 1000 -m -s /usr/sbin/nologin dlp

WORKDIR /app
COPY --chown=dlp:dlp . /app/

# PORTAO DE QUALIDADE: a suite propria do motor roda DENTRO do build, como o
# usuario final, e reprova a imagem se qualquer asseveracao falhar. Ate' agora
# o container tinha milhares de linhas sem um teste automatizado delas mesmas
# (PENDENCIAS, item 10) -- o que existia testava o portal, nao o motor.
RUN python3 /app/testes/executar.py

RUN mkdir -p /dados && chown dlp:dlp /dados
VOLUME ["/dados"]
USER dlp

EXPOSE 8480 1344 10025

HEALTHCHECK --interval=20s --timeout=10s --start-period=20s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8480/saude || exit 1

ENTRYPOINT ["python3", "/app/principal.py"]
