"""
exolib — biblioteca compartilhada da suíte de testes do eXo Platform.

Princípios (exigências do projeto):
  * Nada de teste de fumaça. Um HTTP 200 não prova funcionalidade.
    Todo teste precisa exercer a função real e VERIFICAR O EFEITO
    (o dado criado é recuperável? o conteúdo digitado persistiu?).
  * Dupla abordagem: esta biblioteca serve a abordagem A (máquina/API).
    A abordagem B (navegador real) vive em test_b_browser.py.
  * Toda execução produz evidência bruta em evidence/.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import time
import uuid
from dataclasses import dataclass, field, asdict
from typing import Any

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "evidence"
EVIDENCE.mkdir(exist_ok=True)

BASE = os.environ.get("EXO_BASE", "http://192.168.1.59")
ADMIN_USER = os.environ.get("EXO_ADMIN_USER", "root")
ADMIN_PASS = os.environ.get("EXO_ADMIN_PASS", "pmotiadm")
MAILPIT = os.environ.get("MAILPIT_BASE", "http://192.168.1.59:8025")

# Sufixo único por execução, para não colidir com dados de execuções anteriores
RUN_ID = os.environ.get("RUN_ID") or uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# Registro de resultados
# ---------------------------------------------------------------------------

@dataclass
class Result:
    tid: str                      # T-01, T-02 ...
    name: str
    approach: str                 # "A-maquina" | "B-usuario"
    passed: bool = False
    detail: str = ""
    proof: str = ""               # o que comprova (id criado, texto lido de volta)
    duration_s: float = 0.0
    steps: list = field(default_factory=list)

    def line(self) -> str:
        mark = "PASSOU" if self.passed else "FALHOU"
        return f"[{mark}] {self.tid} ({self.approach}) {self.name} — {self.detail}"


class Recorder:
    """Acumula resultados e grava evidência em JSON + markdown."""

    def __init__(self, label: str):
        self.label = label
        self.results: list[Result] = []
        self.t0 = time.time()

    def add(self, r: Result) -> Result:
        self.results.append(r)
        print(r.line(), flush=True)
        return r

    @property
    def passed(self) -> int:
        return sum(1 for r in self.results if r.passed)

    @property
    def failed(self) -> int:
        return sum(1 for r in self.results if not r.passed)

    def dump(self) -> pathlib.Path:
        payload = {
            "label": self.label,
            "run_id": RUN_ID,
            "base": BASE,
            "started": self.t0,
            "duration_s": round(time.time() - self.t0, 1),
            "total": len(self.results),
            "passed": self.passed,
            "failed": self.failed,
            "results": [asdict(r) for r in self.results],
        }
        p = EVIDENCE / f"resultado-{self.label}-{RUN_ID}.json"
        p.write_text(json.dumps(payload, indent=2, ensure_ascii=False))
        print(f"\n== {self.label}: {self.passed} passaram, {self.failed} falharam "
              f"-> {p.relative_to(ROOT)}", flush=True)
        return p


# ---------------------------------------------------------------------------
# Cliente autenticado
# ---------------------------------------------------------------------------

class ExoClient:
    """
    Sessão HTTP autenticada no eXo.

    O eXo usa autenticação por formulário (JAAS) com cookie JSESSIONID.
    A estratégia de login é DESCOBERTA em tempo de execução em vez de
    presumida, e o método que funcionou fica registrado em .auth_method.
    """

    def __init__(self, user: str = ADMIN_USER, password: str = ADMIN_PASS,
                 base: str = BASE):
        self.base = base.rstrip("/")
        self.user = user
        self.password = password
        self.s = requests.Session()
        self.s.headers["User-Agent"] = "exo-test-suite/1.0"
        self.auth_method = None

    # -- utilidades ---------------------------------------------------------

    def url(self, path: str) -> str:
        return path if path.startswith("http") else f"{self.base}{path}"

    def get(self, path: str, **kw) -> requests.Response:
        kw.setdefault("timeout", 60)
        return self.s.get(self.url(path), **kw)

    def post(self, path: str, **kw) -> requests.Response:
        kw.setdefault("timeout", 60)
        return self.s.post(self.url(path), **kw)

    def put(self, path: str, **kw) -> requests.Response:
        kw.setdefault("timeout", 60)
        return self.s.put(self.url(path), **kw)

    def delete(self, path: str, **kw) -> requests.Response:
        kw.setdefault("timeout", 60)
        return self.s.delete(self.url(path), **kw)

    # -- autenticação -------------------------------------------------------

    def login(self) -> bool:
        """
        Tenta autenticar. Retorna True apenas se a sessão for COMPROVADAMENTE
        válida (um endpoint que exige autenticação responde com dados do
        usuário), não apenas se o POST devolveu 200.
        """
        # Estratégia 1: formulário de login do portal
        try:
            self.s.get(self.url("/portal/login"), timeout=60)
            r = self.s.post(
                self.url("/portal/login"),
                data={"username": self.user, "password": self.password,
                      "rememberme": "false"},
                allow_redirects=True, timeout=60,
            )
            if self.whoami():
                self.auth_method = "form:/portal/login"
                return True
        except requests.RequestException:
            pass

        # Estratégia 2: j_security_check (JAAS clássico)
        try:
            self.s.get(self.url("/portal/dw"), timeout=60)
            self.s.post(
                self.url("/portal/j_security_check"),
                data={"j_username": self.user, "j_password": self.password},
                allow_redirects=True, timeout=60,
            )
            if self.whoami():
                self.auth_method = "form:j_security_check"
                return True
        except requests.RequestException:
            pass

        # Estratégia 3: HTTP Basic
        try:
            self.s.auth = (self.user, self.password)
            if self.whoami():
                self.auth_method = "basic"
                return True
            self.s.auth = None
        except requests.RequestException:
            self.s.auth = None

        return False

    def whoami(self) -> dict | None:
        """
        Confirma a sessão consultando a identidade corrente.
        Retorna o objeto do usuário ou None. NUNCA se baseia só no status HTTP:
        valida que o payload realmente descreve o usuário esperado.

        NOTA (verificado nesta instalação 7.2.1): o endpoint `.../users/me`
        NÃO existe — responde 401 mesmo com sessão válida. O caminho que
        funciona é `.../users/{username}`. Tentar primeiro `/me` produziria
        falso negativo de autenticação, que foi exatamente o que ocorreu
        na primeira execução da suíte.
        """
        for path in (f"/rest/v1/social/users/{self.user}",
                     f"/portal/rest/v1/social/users/{self.user}",
                     "/rest/v1/social/users/me"):
            try:
                r = self.get(path, headers={"Accept": "application/json"},
                             allow_redirects=False)
            except requests.RequestException:
                continue
            if r.status_code != 200:
                continue
            ctype = r.headers.get("Content-Type", "")
            if "json" not in ctype:
                continue          # página de login devolvida como HTTP 200
            try:
                data = r.json()
            except ValueError:
                continue
            if isinstance(data, dict) and (data.get("username") or data.get("id")):
                return data
        return None

    def json_get(self, path: str, **kw) -> tuple[int, Any]:
        r = self.get(path, headers={"Accept": "application/json"}, **kw)
        try:
            return r.status_code, r.json()
        except ValueError:
            return r.status_code, r.text[:400]

    def json_post(self, path: str, payload: dict, **kw) -> tuple[int, Any]:
        r = self.post(path, json=payload,
                      headers={"Accept": "application/json",
                               "Content-Type": "application/json"}, **kw)
        try:
            return r.status_code, r.json()
        except ValueError:
            return r.status_code, r.text[:400]


# ---------------------------------------------------------------------------
# Mailpit — inspeção real das mensagens enviadas
# ---------------------------------------------------------------------------

class Mail:
    def __init__(self, base: str = MAILPIT):
        self.base = base.rstrip("/")

    def clear(self) -> bool:
        try:
            return requests.delete(f"{self.base}/api/v1/messages",
                                   timeout=30).status_code in (200, 204)
        except requests.RequestException:
            return False

    def count(self) -> int:
        try:
            r = requests.get(f"{self.base}/api/v1/messages?limit=1", timeout=30)
            return r.json().get("total", 0)
        except (requests.RequestException, ValueError):
            return -1

    def search(self, term: str) -> list[dict]:
        try:
            r = requests.get(f"{self.base}/api/v1/search",
                             params={"query": term, "limit": 50}, timeout=30)
            return r.json().get("messages", [])
        except (requests.RequestException, ValueError):
            return []

    def wait_for(self, term: str, timeout: int = 120) -> list[dict]:
        """Aguarda até que uma mensagem contendo `term` apareche na caixa."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            hits = self.search(term)
            if hits:
                return hits
            time.sleep(3)
        return []

    def body(self, msg_id: str) -> str:
        try:
            r = requests.get(f"{self.base}/api/v1/message/{msg_id}", timeout=30)
            d = r.json()
            return (d.get("Text") or "") + (d.get("HTML") or "")
        except (requests.RequestException, ValueError):
            return ""


def wait_until(fn, timeout: int = 180, interval: float = 3.0):
    """Repete `fn` até retornar valor verdadeiro ou estourar o tempo."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception:  # noqa: BLE001 — sondagem tolerante por natureza
            pass
        time.sleep(interval)
    return last
