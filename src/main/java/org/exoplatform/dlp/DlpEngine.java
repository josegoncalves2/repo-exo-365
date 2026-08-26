package org.exoplatform.dlp;

import java.util.*;
import java.util.regex.*;

/**
 * DLP Engine — Proteção contra vazamento de dados
 * Bloqueia compartilhamento de arquivos contendo PII/dados sensíveis
 */
public class DlpEngine {

  private static final List<Pattern> PATTERNS = Arrays.asList(
    Pattern.compile("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}"), // CPF
    Pattern.compile("\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}"), // CNPJ
    Pattern.compile("\\b\\d{16}\\b"), // Cartão de crédito
    Pattern.compile("(?i)senha\\s*[:=]\\s*\\S+"), // senha=xxx
    Pattern.compile("(?i)api[_-]?key\\s*[:=]\\s*\\S+"), // api_key=xxx
    Pattern.compile("\\b\\d{1,5}-\\d{4}-\\d{4}\\b") // RG simplificado
  );

  private static final List<String> KEYWORDS = Arrays.asList(
    "cpf", "cnpj", "rg", "pis", "passport", "cartão", "visa", "mastercard",
    "banco", "agência", "conta corrente", "password", "senha", "chave privada"
  );

  public boolean containsSensitiveContent(String content) {
    if (content == null || content.isEmpty()) {
      return false;
    }

    // Validar contra padrões regex
    for (Pattern pattern : PATTERNS) {
      if (pattern.matcher(content).find()) {
        return true;
      }
    }

    // Validar contra palavras-chave
    String lowerContent = content.toLowerCase();
    for (String keyword : KEYWORDS) {
      if (lowerContent.contains(keyword)) {
        return true;
      }
    }

    return false;
  }

  public List<String> detectSensitivePatterns(String content) {
    List<String> detected = new ArrayList<>();

    for (Pattern pattern : PATTERNS) {
      Matcher matcher = pattern.matcher(content);
      while (matcher.find()) {
        detected.add(matcher.group());
      }
    }

    return detected;
  }
}
