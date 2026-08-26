import React from 'react';

/**
 * Componentes que adicionam ícones à navbar do eXo
 * Chat, Videoconferência, Documentos, GLPI
 */

export const NavbarChatIcon = () => (
  <div className="navbar-item" title="Conversa (Alt+M)" data-testid="navbar-chat">
    <a href="/portal/chat" style={{
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      padding: '8px 12px',
      color: '#333',
      textDecoration: 'none',
      borderRadius: '4px'
    }}>
      <span style={{ fontSize: '18px' }}>💬</span>
      <span>Conversa</span>
    </a>
  </div>
);

export const NavbarVideoIcon = () => (
  <div className="navbar-item" title="Videoconferência (Alt+V)" data-testid="navbar-video">
    <a href="/portal/calendar" style={{
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      padding: '8px 12px',
      color: '#333',
      textDecoration: 'none',
      borderRadius: '4px'
    }}>
      <span style={{ fontSize: '18px' }}>📹</span>
      <span>Videochamada</span>
    </a>
  </div>
);

export const NavbarDocumentsIcon = () => (
  <div className="navbar-item" title="Documentos (Alt+D)" data-testid="navbar-documents">
    <a href="/portal/documents" style={{
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      padding: '8px 12px',
      color: '#333',
      textDecoration: 'none',
      borderRadius: '4px'
    }}>
      <span style={{ fontSize: '18px' }}>📄</span>
      <span>Documentos</span>
    </a>
  </div>
);

export const NavbarGLPIIcon = () => (
  <div className="navbar-item" title="Suporte (GLPI) (Alt+G)" data-testid="navbar-glpi">
    <a href="/portal/glpi" style={{
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      padding: '8px 12px',
      color: '#333',
      textDecoration: 'none',
      borderRadius: '4px'
    }}>
      <span style={{ fontSize: '18px' }}>🎟️</span>
      <span>Suporte</span>
    </a>
  </div>
);

export const NavbarEnhancements = () => (
  <div style={{
    display: 'flex',
    gap: '4px',
    alignItems: 'center'
  }}>
    <NavbarChatIcon />
    <NavbarVideoIcon />
    <NavbarDocumentsIcon />
    <NavbarGLPIIcon />
  </div>
);
