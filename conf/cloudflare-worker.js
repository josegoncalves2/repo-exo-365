/**
 * Cloudflare Worker — exo-ai-openai
 * =================================
 * Endpoint OpenAI-compatível que expõe a Workers AI (Cloudflare) ao addon
 * meeds-ai do eXo Platform 7.2.1.
 *
 * POR QUE EXISTE
 * --------------
 * O addon meeds-ai valida provedores via `GET {baseUrl}/v1/models` com
 * `Authorization: Bearer {apiKey}`. A Workers AI NÃO implementa esse endpoint
 * (retorna 405 "GET not supported"). Este Worker responde a validação e faz
 * proxy das chamadas de chat/embeddings para a Workers AI.
 *
 * DEPLOY (com o token da conta):
 *   curl -X PUT "https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/exo-ai-openai" \
 *     -H "Authorization: Bearer {API_TOKEN}" \
 *     -H "Content-Type: application/javascript" \
 *     --data-binary @conf/cloudflare-worker.js
 *
 * URL pública: https://exo-ai-openai.lab-hml01.workers.dev
 *
 * CONFIGURAÇÃO NO eXo (Models → Add Provider → Open AI Compatible):
 *   - Url:               https://exo-ai-openai.lab-hml01.workers.dev
 *   - API Key:           qualquer valor não-vazio (o Worker injeta o token real)
 *   - Completion API Path: /v1/chat/completions
 *
 * NOTA IMPORTANTE (bug do addon): o frontend envia `nameId: null` na criação,
 * e o @CachePut(key='#p0.nameId') do ProviderStorage lança "Null key returned"
 * → POST 400 mesmo salvando o provider. Criar o provider com `nameId` definido
 * (ex.: "cloudflare") via o mesmo endpoint REST da interface:
 *   POST /ai-agent/rest/administration/providers
 *   {"enabled":true,"nameId":"cloudflare","providerId":"openaiCompatible",
 *    "baseUrl":"https://exo-ai-openai.lab-hml01.workers.dev",
 *    "apiKey":"qualquer","completionPath":"/v1/chat/completions"}
 */

const ACCOUNT_ID = '6063067dd1ba49ab929cbc3f2c0ffa67'; // conta lab-hml01 (não é segredo)
const BASE = 'https://api.cloudflare.com/client/v4/accounts/' + ACCOUNT_ID + '/ai/v1';

// eXo (meeds-ai) ModelListingUtil lê `data` (NÃO "models"): GET /v1/models -> { "data": [ { "id": ..., "owned_by": ... } ] }
// Modelos verificados disponíveis na Workers AI em 2026-08-26.
const MODELS = {
  data: [
    { id: '@cf/meta/llama-3.3-70b-instruct-fp8-fast', owned_by: 'cloudflare' },
    { id: '@cf/meta/llama-3.2-3b-instruct', owned_by: 'cloudflare' },
    { id: '@cf/meta/llama-3.2-1b-instruct', owned_by: 'cloudflare' },
    { id: '@cf/meta/llama-3.1-8b-instruct-fp8', owned_by: 'cloudflare' },
    { id: '@cf/qwen/qwen3-embedding-0.6b', owned_by: 'cloudflare' },
    { id: '@cf/baai/bge-m3', owned_by: 'cloudflare' },
    { id: '@cf/baai/bge-base-en-v1.5', owned_by: 'cloudflare' },
    { id: '@cf/baai/bge-large-en-v1.5', owned_by: 'cloudflare' },
  ]
};

addEventListener('fetch', (event) => {
  event.respondWith(handle(event.request));
});

async function handle(request) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  // Validação usada por AiProviderService.testProviderConnection / getAvailableModels
  if (method === 'GET' && path === '/v1/models') {
    return new Response(JSON.stringify(MODELS), {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  // Proxy OpenAI-compatível para a Workers AI
  if (method === 'POST' && (path === '/v1/embeddings' || path === '/v1/chat/completions')) {
    const body = await request.text();
    const targetPath = path.replace('/v1', '');
    // Formato clássico (addEventListener): secrets são injetados como globais
    const apiToken = (typeof API_TOKEN !== 'undefined' && API_TOKEN) || globalThis.API_TOKEN;
    const resp = await fetch(BASE + targetPath, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + apiToken,
        'Content-Type': 'application/json',
      },
      body: body,
    });
    const text = await resp.text();
    return new Response(text, {
      status: resp.status,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
}
