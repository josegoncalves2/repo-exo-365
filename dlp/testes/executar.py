#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Roda a suite do motor. E' PORTAO DE BUILD: falha aqui reprova a imagem.

Uso:
    python3 /app/testes/executar.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from testes import apoio                                    # noqa: E402
from testes import t_cofre        # noqa: E402,F401
from testes import t_cripto       # noqa: E402,F401
from testes import t_motor        # noqa: E402,F401
from testes import t_acoes        # noqa: E402,F401
from testes import t_servico      # noqa: E402,F401
from testes import t_descoberta   # noqa: E402,F401
from testes import t_canais       # noqa: E402,F401
from testes import t_api          # noqa: E402,F401

if __name__ == "__main__":
    sys.exit(apoio.executar_todos())
