#!/usr/bin/env python3
"""
Script para diagnosticar e corrigir problemas de API do eXo.
Testa múltiplas abordagens para criar usuários e espaços.
"""
import sys
import os
import json
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "tests"))
from exolib import ExoClient, ADMIN_USER, ADMIN_PASS, BASE

def test_user_creation():
    """Testa criação de usuário com diferentes endpoints."""
    c = ExoClient(ADMIN_USER, ADMIN_PASS)
    if not c.login():
        print("❌ Falha ao autenticar")
        return False

    uname = f"testfix{int(time.time())}"
    pwd = "Test@123"
    payload = {"userName": uname, "password": pwd, "firstName": "Test", "lastName": "User"}

    print("\n=== TESTE DE CRIAÇÃO DE USUÁRIO ===")
    print(f"Usuário: {uname}")

    endpoints = [
        ("/rest/v1/users", "POST"),
        ("/rest/v1/social/users", "POST"),
        ("/rest/management/users", "POST"),
        ("/portal/rest/v1/users", "POST"),
    ]

    for endpoint, method in endpoints:
        try:
            resp = c.post(endpoint, json=payload,
                         headers={"Content-Type": "application/json", "Accept": "application/json"},
                         timeout=10, allow_redirects=False)
            print(f"\n{method} {endpoint}")
            print(f"  Status: {resp.status_code}")
            if resp.text:
                print(f"  Response: {resp.text[:150]}")

            # Verifica se criou realmente
            if resp.status_code in (200, 201, 204):
                novo = ExoClient(uname, pwd)
                if novo.login():
                    me = novo.whoami()
                    print(f"  ✅ USUÁRIO CRIADO E LOGOU: {me.get('username')}")
                    return True
                else:
                    print(f"  ⚠️  API retornou OK mas usuário não loga")
        except Exception as e:
            print(f"\n{method} {endpoint}")
            print(f"  ❌ Erro: {e}")

    return False

def test_space_creation():
    """Testa criação de espaço."""
    c = ExoClient(ADMIN_USER, ADMIN_PASS)
    if not c.login():
        return False

    sname = f"EspacoTest{int(time.time())}"
    payload = {"displayName": sname, "description": "Teste"}

    print("\n=== TESTE DE CRIAÇÃO DE ESPAÇO ===")
    print(f"Espaço: {sname}")

    endpoints = [
        "/rest/v1/social/spaces",
        "/portal/rest/v1/social/spaces",
    ]

    for endpoint in endpoints:
        try:
            resp = c.post(endpoint, json=payload,
                         headers={"Content-Type": "application/json", "Accept": "application/json"},
                         timeout=10, allow_redirects=False)
            print(f"\nPOST {endpoint}")
            print(f"  Status: {resp.status_code}")
            if resp.text:
                print(f"  Response: {resp.text[:150]}")

            if resp.status_code in (200, 201):
                return True
        except Exception as e:
            print(f"\nPOST {endpoint}")
            print(f"  ❌ Erro: {e}")

    return False

def check_permissions():
    """Verifica permissões do usuário root."""
    c = ExoClient(ADMIN_USER, ADMIN_PASS)
    if not c.login():
        return

    print("\n=== PERMISSÕES DO USUÁRIO ROOT ===")

    # Tenta acessar endpoints administrativos
    endpoints = [
        "/rest/management/users",
        "/rest/v1/users",
        "/rest/v1/groups",
        "/rest/v1/social/spaces",
    ]

    for endpoint in endpoints:
        try:
            resp = c.get(endpoint + "?limit=1", timeout=5)
            print(f"\nGET {endpoint}")
            print(f"  Status: {resp.status_code}")
            if resp.status_code == 200:
                print(f"  ✅ Acesso permitido")
        except Exception as e:
            print(f"\nGET {endpoint}")
            print(f"  ❌ Erro: {e}")

if __name__ == "__main__":
    print("=" * 70)
    print("DIAGNÓSTICO DE API DO EXO")
    print("=" * 70)

    check_permissions()
    test_user_creation()
    test_space_creation()

    print("\n" + "=" * 70)
    print("FIM DO DIAGNÓSTICO")
    print("=" * 70)
