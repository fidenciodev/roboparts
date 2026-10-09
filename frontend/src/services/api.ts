const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? "").trim().replace(/\/+$/, "");

export class ApiError extends Error {
  readonly status?: number;
  readonly code?: string;
  readonly fields: Record<string, string>;

  constructor(message: string, status?: number, code?: string, fields: Record<string, string> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
  }
}

const messages: Record<string, string> = {
  concurrent_update: "Outra operação alterou os dados. Clique em Atualizar, confira as mudanças e salve novamente.",
  not_found: "O registro não foi encontrado. Volte à lista e atualize os dados.",
  robot_archived: "Este robô está arquivado. Restaure-o antes de alterar a estrutura ou iniciar uma retirada.",
  invalid_parent: "Escolha uma categoria ativa pertencente ao mesmo robô.",
  invalid_hierarchy: "Esta organização criaria um ciclo ou excederia o limite de 32 níveis.",
  structure_limit: "O robô atingiu o limite de 1000 elementos.",
  empty_structure: "Adicione pelo menos um componente ativo ao robô antes de iniciar uma retirada.",
  duplicate_operation: "Este identificador já foi usado em outra operação. Atualize os dados antes de continuar.",
  checklist_closed: "O checklist está encerrado ou esta operação não é permitida no estado atual.",
  invalid_quantity: "Informe uma quantidade inteira entre zero e a quantidade necessária.",
  pending_components: "Confira todos os componentes obrigatórios e registre uma retirada antes de finalizar.",
  invalid_credentials: "E-mail ou senha inválidos. Confira os dados e tente novamente.",
  invalid_access_code: "O código de acesso é inválido. Solicite um código válido à sua equipe.",
  email_already_registered: "Este e-mail já está cadastrado. Entre com sua conta.",
  registration_disabled: "O cadastro está indisponível. Solicite à sua equipe a configuração do código de acesso.",
  validation_error: "Confira os campos indicados e tente novamente.",
  unauthenticated: "Sua sessão expirou. Entre novamente para continuar.",
  access_denied: "Você não tem acesso a esta operação.",
  csrf_invalid: "Não foi possível validar esta solicitação. Tente novamente.",
  rate_limited: "Muitas tentativas em pouco tempo. Aguarde alguns minutos antes de tentar novamente.",
  service_unavailable: "O serviço está temporariamente indisponível. Tente novamente em instantes.",
  internal_error: "Não foi possível concluir a solicitação. Tente novamente em instantes.",
};
const fieldMessages: Record<string, string> = {
  name: "Informe um nome válido, com 2 a 100 caracteres.",
  email: "Informe um e-mail válido, com até 254 caracteres.",
  password: "Use uma senha de 12 a 128 caracteres.",
  accessCode: "Confira o código de acesso informado.",
};
const expiredListeners = new Set<() => void>();
export function onSessionExpired(listener: () => void) {
  expiredListeners.add(listener);
  return () => { expiredListeners.delete(listener); };
}

interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  notifyUnauthorized?: boolean;
}

async function rawRequest(path: string, options: RequestOptions = {}): Promise<unknown> {
  const timeoutSignal = AbortSignal.timeout(10_000);
  const requestSignal = options.signal
    ? AbortSignal.any([options.signal, timeoutSignal])
    : timeoutSignal;

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: options.method ?? "GET",
      credentials: "include",
      headers: {
        Accept: "application/json",
        ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
      ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
      signal: requestSignal,
      cache: "no-store",
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    if (timeoutSignal.aborted) {
      throw new ApiError(
        "A solicitação excedeu 10 segundos. Confira se o backend está em execução.",
      );
    }
    throw new ApiError(
      "Não foi possível acessar o backend. Confira se ele está em execução e tente novamente.",
    );
  }

  // Uma resposta de uma consulta cancelada não pode invalidar uma sessão mais recente.
  options.signal?.throwIfAborted();
  if (!response.ok) {
    if (response.status === 401 && options.notifyUnauthorized !== false) {
      invalidateCsrf();
      expiredListeners.forEach((listener) => listener());
    }
    let problem: unknown;
    try { problem = await response.json(); } catch { /* Nunca exibir HTML ou detalhes internos. */ }
    const record = typeof problem === "object" && problem !== null ? problem as Record<string, unknown> : {};
    const code = typeof record.code === "string" ? record.code : undefined;
    const fields: Record<string, string> = {};
    if (code === "validation_error" && typeof record.errors === "object" && record.errors !== null) {
      Object.keys(record.errors).forEach((field) => { if (fieldMessages[field]) fields[field] = fieldMessages[field]; });
    }
    const fallback = response.status === 401 ? messages.unauthenticated
      : response.status === 429 ? messages.rate_limited
      : `O backend respondeu com erro HTTP ${response.status}. Tente novamente em instantes.`;
    if (code === "csrf_invalid") invalidateCsrf();
    throw new ApiError(code && messages[code] ? messages[code] : fallback, response.status, code, fields);
  }

  if (response.status === 204) return undefined;
  try {
    return await response.json();
  } catch {
    throw new ApiError(
      "O backend enviou uma resposta inválida. Verifique a configuração da API.",
    );
  }
}

interface CsrfToken { token: string; headerName: "X-CSRF-TOKEN" }
let csrfToken: CsrfToken | undefined;
let pendingCsrf: Promise<CsrfToken> | undefined;
let csrfVersion = 0;

export function invalidateCsrf() {
  csrfVersion += 1;
  csrfToken = undefined;
  pendingCsrf = undefined;
}

async function getCsrf(): Promise<CsrfToken> {
  if (csrfToken) return csrfToken;
  if (pendingCsrf) return pendingCsrf;
  const version = csrfVersion;
  // A requisição compartilhada usa seu próprio prazo; um formulário cancelado não cancela os demais.
  const pending = rawRequest("/api/auth/csrf", { notifyUnauthorized: false }).then((data) => {
    if (typeof data !== "object" || data === null || !("token" in data) ||
      typeof data.token !== "string" || !data.token || !("headerName" in data) || data.headerName !== "X-CSRF-TOKEN") {
      throw new ApiError("Não foi possível preparar uma solicitação segura. Tente novamente.");
    }
    const result: CsrfToken = { token: data.token, headerName: data.headerName };
    if (version === csrfVersion) csrfToken = result;
    return result;
  }).finally(() => { if (pendingCsrf === pending) pendingCsrf = undefined; });
  pendingCsrf = pending;
  return pending;
}

export async function refreshCsrf() {
  invalidateCsrf();
  // A operação concluída continua válida se a renovação falhar; o próximo POST buscará um novo token.
  try { await getCsrf(); } catch { /* Próxima solicitação poderá tentar novamente. */ }
}

function withAbort<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(signal.reason);
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    signal.addEventListener("abort", abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

export async function apiRequest(path: string, options: RequestOptions = {}): Promise<unknown> {
  if (options.method === "POST") {
    const csrf = await withAbort(getCsrf(), options.signal);
    options.signal?.throwIfAborted();
    // Um POST nunca é repetido automaticamente: evita executar a mesma operação duas vezes.
    return rawRequest(path, { ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } });
  }
  return rawRequest(path, options);
}

export function apiGet(path: string, signal?: AbortSignal): Promise<unknown> {
  return apiRequest(path, { signal });
}
