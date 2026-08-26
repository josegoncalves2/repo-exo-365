/**
 * Mapeamento global de atalhos de teclado
 * Standardizado conforme design system
 */

const SHORTCUTS = {
  'Alt+M': {
    name: 'Conversa',
    url: '/portal/chat',
    description: 'Abrir módulo de conversas'
  },
  'Alt+V': {
    name: 'Videochamada',
    url: '/portal/calendar',
    description: 'Abrir agenda com videoconferência'
  },
  'Alt+D': {
    name: 'Documentos',
    url: '/portal/documents',
    description: 'Abrir gestor de documentos'
  },
  'Alt+G': {
    name: 'Suporte (GLPI)',
    url: '/portal/glpi',
    description: 'Abrir sistema de suporte'
  },
  'Ctrl+K': {
    name: 'Busca Rápida',
    url: null,
    callback: 'openSearch',
    description: 'Abrir busca global'
  },
  'Ctrl+?': {
    name: 'Ajuda',
    url: null,
    callback: 'openHelp',
    description: 'Listar todos os atalhos'
  }
};

class KeyboardShortcutManager {
  constructor() {
    this.shortcuts = SHORTCUTS;
    this.setupListeners();
  }

  setupListeners() {
    document.addEventListener('keydown', (e) => this.handleKeyPress(e));
  }

  handleKeyPress(event) {
    const key = this.getKeyCombo(event);
    const shortcut = this.shortcuts[key];

    if (!shortcut) return;

    event.preventDefault();

    if (shortcut.url) {
      window.location.href = shortcut.url;
    } else if (shortcut.callback) {
      this[shortcut.callback]?.();
    }
  }

  getKeyCombo(event) {
    const parts = [];
    if (event.ctrlKey) parts.push('Ctrl');
    if (event.altKey) parts.push('Alt');
    if (event.shiftKey) parts.push('Shift');

    const key = event.key.toUpperCase();
    if (key !== 'CONTROL' && key !== 'ALT' && key !== 'SHIFT') {
      parts.push(key);
    }

    return parts.join('+');
  }

  openSearch() {
    console.log('Abrindo busca...');
    const searchInput = document.querySelector('input[type="search"]');
    if (searchInput) searchInput.focus();
  }

  openHelp() {
    alert('Atalhos Disponíveis:\n\n' +
          Object.entries(this.shortcuts)
            .map(([key, value]) => `${key}: ${value.description}`)
            .join('\n'));
  }

  getShortcuts() {
    return this.shortcuts;
  }
}

export const keyboardManager = new KeyboardShortcutManager();
