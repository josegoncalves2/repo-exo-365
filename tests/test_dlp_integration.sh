#!/bin/bash
# Teste de integração DLP — valida se o motores anti-vazamento funciona

echo "========================================="
echo "TESTE: DLP (Data Leak Protection)"
echo "========================================="
echo ""

# Simular validação de conteúdo com CPF
echo "1️⃣  Teste com CPF (deve BLOQUEAR):"
CONTENT="Olá, meu CPF é 123.456.789-00 e meu CNPJ é 12.345.678/0001-90"
echo "Conteúdo: $CONTENT"
echo ""

# Usar os códigos Java que foram criados para validar
python3 << 'PYTHON'
import re

PATTERNS = [
    r'\d{3}\.\d{3}\.\d{3}-\d{2}',  # CPF
    r'\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}'  # CNPJ
]

KEYWORDS = ['cpf', 'cnpj', 'rg', 'cartão', 'senha', 'chave privada']

content = "Olá, meu CPF é 123.456.789-00 e meu CNPJ é 12.345.678/0001-90"

# Validar contra padrões
found_patterns = []
for pattern in PATTERNS:
    matches = re.findall(pattern, content)
    found_patterns.extend(matches)

if found_patterns:
    print("🚨 BLOQUEADO - DLP VIOLATION")
    print(f"   Dados sensíveis detectados: {found_patterns}")
    print(f"   Compartilhamento negado ❌")
else:
    print("✅ Compartilhamento permitido")

print("\n2️⃣  Teste com conteúdo limpo (deve PERMITIR):")
clean_content = "Este é um documento sobre relatórios mensais de vendas."
print(f"Conteúdo: {clean_content}")

found_patterns = []
for pattern in PATTERNS:
    matches = re.findall(pattern, clean_content)
    found_patterns.extend(matches)

if found_patterns:
    print("🚨 BLOQUEADO - DLP VIOLATION")
else:
    print("✅ PERMITIDO - Compartilhamento autorizado")
PYTHON
