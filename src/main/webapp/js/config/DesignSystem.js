/**
 * Design System Central — Define padrão visual único para toda plataforma
 * Cores, tipografia, spacing, componentes
 */

export const DESIGN_SYSTEM = {
  version: '1.0.0',
  brand: 'PMO eXo',

  colors: {
    primary: '#0066cc',
    primaryHover: '#0052a3',
    secondary: '#ff6600',
    success: '#00cc00',
    warning: '#ffcc00',
    error: '#ff0000',
    neutral: {
      50: '#f9f9f9',
      100: '#f0f0f0',
      200: '#e0e0e0',
      500: '#808080',
      900: '#1a1a1a'
    }
  },

  typography: {
    headings: {
      family: 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI',
      h1: { size: '32px', weight: '700', lineHeight: '1.2' },
      h2: { size: '24px', weight: '600', lineHeight: '1.3' },
      h3: { size: '20px', weight: '600', lineHeight: '1.3' }
    },
    body: {
      family: 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI',
      size: '14px',
      weight: '400',
      lineHeight: '1.5'
    },
    code: {
      family: 'JetBrains Mono, monospace',
      size: '13px',
      weight: '400',
      lineHeight: '1.4'
    }
  },

  spacing: {
    xs: '4px',
    sm: '8px',
    md: '16px',
    lg: '24px',
    xl: '32px',
    xxl: '48px'
  },

  components: {
    button: {
      primary: {
        bg: '#0066cc',
        text: 'white',
        padding: '10px 16px',
        borderRadius: '6px',
        fontSize: '14px',
        fontWeight: '600',
        border: 'none',
        cursor: 'pointer'
      },
      secondary: {
        bg: '#f0f0f0',
        text: '#1a1a1a',
        padding: '10px 16px',
        borderRadius: '6px',
        fontSize: '14px',
        fontWeight: '600',
        border: '1px solid #e0e0e0',
        cursor: 'pointer'
      }
    },
    input: {
      border: '1px solid #ccc',
      borderRadius: '6px',
      padding: '8px 12px',
      fontSize: '14px',
      fontFamily: 'Inter, -apple-system',
      '&:focus': {
        borderColor: '#0066cc',
        outline: 'none',
        boxShadow: '0 0 0 3px rgba(0, 102, 204, 0.1)'
      }
    }
  },

  naming: {
    convention: 'Title Case',
    language: 'pt-BR',
    preserveAccents: true
  }
};

// Função helper para aplicar theme
export const applyDesignSystem = () => {
  const style = document.createElement('style');
  style.innerHTML = `
    :root {
      --color-primary: ${DESIGN_SYSTEM.colors.primary};
      --color-secondary: ${DESIGN_SYSTEM.colors.secondary};
      --color-success: ${DESIGN_SYSTEM.colors.success};
      --font-body: ${DESIGN_SYSTEM.typography.body.family};
      --font-heading: ${DESIGN_SYSTEM.typography.headings.family};
      --spacing-md: ${DESIGN_SYSTEM.spacing.md};
    }

    * {
      font-family: var(--font-body);
    }

    h1, h2, h3 {
      font-family: var(--font-heading);
    }

    .btn-primary {
      background-color: var(--color-primary);
      color: white;
      padding: 10px 16px;
      border-radius: 6px;
      border: none;
      cursor: pointer;
      font-weight: 600;
    }

    .btn-primary:hover {
      background-color: #0052a3;
    }

    input, textarea, select {
      border: 1px solid #ccc;
      border-radius: 6px;
      padding: 8px 12px;
      font-family: var(--font-body);
    }

    input:focus, textarea:focus, select:focus {
      border-color: var(--color-primary);
      outline: none;
      box-shadow: 0 0 0 3px rgba(0, 102, 204, 0.1);
    }
  `;
  document.head.appendChild(style);
};
