import React, { useState, useEffect } from 'react';

export const ChatWidget = () => {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [isOpen, setIsOpen] = useState(false);

  useEffect(() => {
    fetchMessages();
  }, []);

  const fetchMessages = async () => {
    try {
      const response = await fetch('/portal/rest/v1/matrix/messages');
      if (response.ok) {
        setMessages(await response.json());
      }
    } catch (e) {
      console.error('Erro ao carregar mensagens', e);
    }
  };

  const sendMessage = async () => {
    if (!input.trim()) return;

    try {
      const response = await fetch('/portal/rest/v1/matrix/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: input })
      });

      if (response.ok) {
        setInput('');
        fetchMessages();
      }
    } catch (e) {
      console.error('Erro ao enviar mensagem', e);
    }
  };

  return (
    <div className="chat-widget" style={{
      position: 'fixed',
      bottom: '20px',
      right: '20px',
      width: '350px',
      backgroundColor: 'white',
      borderRadius: '8px',
      boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
      zIndex: 1000
    }}>
      <div style={{
        padding: '12px',
        backgroundColor: '#0066cc',
        color: 'white',
        borderRadius: '8px 8px 0 0',
        cursor: 'pointer',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }} onClick={() => setIsOpen(!isOpen)}>
        <span>💬 Conversa</span>
        <button style={{ background: 'none', border: 'none', color: 'white', cursor: 'pointer' }}>
          {isOpen ? '▼' : '▲'}
        </button>
      </div>

      {isOpen && (
        <div style={{ padding: '12px' }}>
          <div style={{
            maxHeight: '300px',
            overflowY: 'auto',
            marginBottom: '12px',
            borderBottom: '1px solid #eee',
            paddingBottom: '12px'
          }}>
            {messages.map((msg, idx) => (
              <div key={idx} style={{
                marginBottom: '8px',
                padding: '8px',
                backgroundColor: '#f5f5f5',
                borderRadius: '4px',
                fontSize: '12px'
              }}>
                <strong>{msg.author}</strong>: {msg.text}
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', gap: '8px' }}>
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyPress={(e) => e.key === 'Enter' && sendMessage()}
              placeholder="Digite uma mensagem..."
              style={{
                flex: 1,
                padding: '8px',
                border: '1px solid #ccc',
                borderRadius: '4px',
                fontSize: '12px'
              }}
            />
            <button
              onClick={sendMessage}
              style={{
                padding: '8px 12px',
                backgroundColor: '#0066cc',
                color: 'white',
                border: 'none',
                borderRadius: '4px',
                cursor: 'pointer',
                fontSize: '12px'
              }}
            >
              Enviar
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
