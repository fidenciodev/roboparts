import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../contexts/AuthContext";
import { invalidateCsrf } from "../services/api";
import { AppRoutes } from "./AppRoutes";
import { safeReturnPath } from "./ProtectedRoute";

const currentUser = { id: "ab70c719-b047-4f31-97c0-d85280cda031", name: "Ana Silva", email: "ana@example.com" };
const validStatus = {
  application: "RoboParts", status: "UP", database: { status: "UP", version: "18.3" },
  migrations: { version: "2", description: "Employee authentication" },
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const anonymous = () => json({ code: "unauthenticated" }, 401);
type ApiHandler = (options: RequestInit) => Response | Promise<Response>;

function installApi(overrides: Record<string, ApiHandler> = {}) {
  const defaults: Record<string, ApiHandler> = {
    "/api/auth/me": () => json(currentUser),
    "/api/auth/csrf": () => json({ token: "masked-token", headerName: "X-CSRF-TOKEN" }),
    "/api/auth/login": () => json(currentUser),
    "/api/auth/register": () => json(currentUser, 201),
    "/api/auth/logout": () => new Response(null, { status: 204 }),
    "/api/auth/activities?limit=20": () => json([]),
    "/api/system/status": () => json(validStatus),
    "/api/dashboard": () => json({robots:0,robotsInUse:0,inProgress:0,completed:0,pendingComponents:0,recent:[]}),
  };
  const fetchMock = vi.fn().mockImplementation((path: string, options: RequestInit) => {
    const handler = overrides[path] ?? defaults[path];
    if (!handler) throw new Error(`Unexpected test request: ${path}`);
    return Promise.resolve(handler(options));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location" hidden>{location.pathname}{location.search}{location.hash}</output>;
}

function renderApp(path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <AuthProvider><AppRoutes /></AuthProvider><LocationProbe />
  </MemoryRouter></QueryClientProvider>);
  return client;
}

async function fillLogin(password = "valid-password-123") {
  await userEvent.type(screen.getByLabelText("E-mail"), currentUser.email);
  await userEvent.type(screen.getByLabelText("Senha"), password);
}

async function fillRegistration(confirmation = "valid-password-123") {
  await userEvent.type(screen.getByLabelText("Nome"), " Ana Silva ");
  await userEvent.type(screen.getByLabelText("E-mail"), " ana@example.com ");
  await userEvent.type(screen.getByLabelText("Senha"), "valid-password-123");
  await userEvent.type(screen.getByLabelText("Confirmar senha"), confirmation);
  await userEvent.type(screen.getByLabelText("Código de acesso"), "team-access-code");
}

function expectOnlyThemeStored() {
  expect(localStorage.length).toBe(1);
  expect(localStorage.key(0)).toBe("roboparts-theme");
  expect(localStorage.getItem("roboparts-theme")).toBe("light");
  expect(sessionStorage.length).toBe(0);
}

beforeEach(() => invalidateCsrf());

describe("Sessão e navegação privada", () => {
  it("aguarda a recuperação da sessão antes de exibir páginas privadas", async () => {
    let resolveSession!: (response: Response) => void;
    const fetchMock = installApi({ "/api/auth/me": () => new Promise<Response>((resolve) => { resolveSession = resolve; }) });
    renderApp();
    expect(screen.getByRole("heading", { name: "Verificando seu acesso" })).toBeInTheDocument();
    expect(screen.queryByText("Ana Silva")).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await act(async () => resolveSession(json(currentUser)));
    expect(await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." })).toBeInTheDocument();
    expect(within(screen.getByLabelText("Funcionário conectado")).getByText(currentUser.email)).toBeInTheDocument();
  });

  it("preserva uma rota privada com busca e fragmento e a abre depois do login", async () => {
    const fetchMock = installApi({ "/api/auth/me": anonymous });
    renderApp("/sistema?local=1#diagnostico");
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    await fillLogin();
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(await screen.findByRole("heading", { name: "Uma base conectada." })).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/sistema?local=1#diagnostico");
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/auth/login")).toHaveLength(1);
    expectOnlyThemeStored();
  });

  it("redireciona um funcionário autenticado da página de login para seu espaço", async () => {
    installApi();
    renderApp("/entrar");
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
  });

  it("permite verificar a sessão novamente quando o backend estiver indisponível", async () => {
    let available = false;
    installApi({ "/api/auth/me": () => {
      if (!available) return Promise.reject(new TypeError("offline"));
      return json(currentUser);
    } });
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível acessar o backend");
    expect(screen.queryByRole("heading", { name: "Bem-vindo, Ana Silva." })).not.toBeInTheDocument();
    available = true;
    await userEvent.click(screen.getByRole("button", { name: "Tentar novamente" }));
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
  });

  it("remove identidade e dados privados quando uma API responde 401", async () => {
    let expired = false;
    installApi({ "/api/auth/activities?limit=20": () => expired ? anonymous() : json([
      { id: "activity-1", action: "USER_LOGGED_IN", createdAt: "2026-10-09T12:00:00Z" },
    ]) });
    const client = renderApp();
    await screen.findByText("Entrada no sistema");
    client.setQueryData(["private", "example"], { secret: "private-data" });
    expired = true;
    await userEvent.click(screen.getByRole("button", { name: "Atualizar atividade" }));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(screen.getByText("Sua sessão expirou. Entre novamente para continuar.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Funcionário conectado")).not.toBeInTheDocument();
    expect(screen.queryByText("Entrada no sistema")).not.toBeInTheDocument();
    expect(client.getQueryData(["private", "example"])).toBeUndefined();
  });

  it.each(["https://outside.example", "//outside.example", "/%2foutside.example", "/\\outside.example", "/entrar", "/cadastro", "/%5coutside.example", "/%00outside.example"])(
    "descarta destinos de retorno inválidos (%s)", (path) => expect(safeReturnPath(path)).toBe("/"),
  );
  it("preserva busca e fragmento de um destino interno válido", () => {
    expect(safeReturnPath("/sistema?local=1#diagnostico")).toBe("/sistema?local=1#diagnostico");
  });
});

describe("Formulários de acesso", () => {
  it("valida o formulário de login, foca o primeiro campo inválido e não chama a API", async () => {
    const fetchMock = installApi({ "/api/auth/me": anonymous });
    renderApp("/entrar");
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(screen.getByLabelText("E-mail")).toHaveFocus();
    expect(screen.getByLabelText("E-mail")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("Senha")).toHaveAccessibleDescription("Informe sua senha de 12 a 128 caracteres.");
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/me"]);
  });

  it("desabilita o formulário enquanto entra e envia apenas um login", async () => {
    let resolveLogin!: (response: Response) => void;
    const fetchMock = installApi({ "/api/auth/me": anonymous,
      "/api/auth/login": () => new Promise<Response>((resolve) => { resolveLogin = resolve; }),
    });
    renderApp("/entrar");
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    await fillLogin();
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(screen.getByRole("button", { name: "Entrando…" })).toBeDisabled();
    expect(screen.getByLabelText("Senha")).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Entrando…" }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === "/api/auth/login")).toHaveLength(1));
    await act(async () => resolveLogin(json(currentUser)));
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    expectOnlyThemeStored();
  });

  it("apresenta credenciais inválidas sem transformar o erro em expiração e limpa a senha", async () => {
    installApi({ "/api/auth/me": anonymous, "/api/auth/login": () => json({ code: "invalid_credentials" }, 401) });
    renderApp("/entrar");
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    await fillLogin();
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("E-mail ou senha inválidos");
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    expect(screen.getByLabelText("E-mail")).toHaveValue(currentUser.email);
    expect(screen.queryByText("Sua sessão expirou. Entre novamente para continuar.")).not.toBeInTheDocument();
  });

  it("exige confirmação da senha e após o cadastro mostra login sem autenticar automaticamente", async () => {
    const fetchMock = installApi({ "/api/auth/me": anonymous });
    renderApp("/cadastro");
    await screen.findByRole("heading", { name: "Seu acesso começa aqui." });
    expect(screen.getByLabelText("Nome")).toHaveAttribute("maxlength", "100");
    expect(screen.getByLabelText("Código de acesso")).toHaveAttribute("maxlength", "256");
    await userEvent.click(screen.getByRole("button", { name: "Criar minha conta" }));
    expect(screen.getByLabelText("Nome")).toHaveFocus();
    await fillRegistration("different-password");
    await userEvent.click(screen.getByRole("button", { name: "Criar minha conta" }));
    expect(screen.getByLabelText("Confirmar senha")).toHaveFocus();
    expect(screen.getByText("A confirmação precisa ser igual à senha.")).toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/me"]);
    await userEvent.clear(screen.getByLabelText("Confirmar senha"));
    await userEvent.type(screen.getByLabelText("Confirmar senha"), "valid-password-123");
    await userEvent.click(screen.getByRole("button", { name: "Criar minha conta" }));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(screen.getByRole("status")).toHaveTextContent("Cadastro concluído. Entre com seu e-mail e senha.");
    const registrations = fetchMock.mock.calls.filter(([path]) => path === "/api/auth/register");
    expect(registrations).toHaveLength(1);
    expect(JSON.parse(registrations[0][1].body)).toEqual({
      name: "Ana Silva", email: currentUser.email, password: "valid-password-123", accessCode: "team-access-code",
    });
    expect(fetchMock.mock.calls.some(([path]) => path === "/api/auth/login")).toBe(false);
    expectOnlyThemeStored();
  });

  it("exibe código inválido e limpa senha, confirmação e código mantendo nome e e-mail", async () => {
    installApi({ "/api/auth/me": anonymous, "/api/auth/register": () => json({ code: "invalid_access_code" }, 403) });
    renderApp("/cadastro");
    await screen.findByRole("heading", { name: "Seu acesso começa aqui." });
    await fillRegistration();
    await userEvent.click(screen.getByRole("button", { name: "Criar minha conta" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("O código de acesso é inválido");
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    expect(screen.getByLabelText("Confirmar senha")).toHaveValue("");
    expect(screen.getByLabelText("Código de acesso")).toHaveValue("");
    expect(screen.getByLabelText("Nome")).toHaveValue(" Ana Silva ");
    expect(screen.getByLabelText("E-mail")).toHaveValue(currentUser.email);
  });
});

describe("Saída e atividade pessoal", () => {
  it("encerra a sessão e limpa o cache privado após logout confirmado", async () => {
    const fetchMock = installApi();
    const client = renderApp();
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    client.setQueryData(["private", "example"], { secret: "private-data" });
    await userEvent.click(screen.getByRole("button", { name: "Sair" }));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(client.getQueryData(["private", "example"])).toBeUndefined();
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/auth/logout")).toHaveLength(1);
  });

  it("preserva a identidade e permite tentar novamente quando a saída não for confirmada", async () => {
    const fetchMock = installApi({ "/api/auth/logout": () => Promise.reject(new TypeError("offline")) });
    renderApp();
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    await userEvent.click(screen.getByRole("button", { name: "Sair" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível acessar o backend");
    expect(screen.getByRole("heading", { name: "Bem-vindo, Ana Silva." })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sair" })).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/auth/logout")).toHaveLength(1);
  });

  it("remove a identidade local quando o logout informa sessão já expirada", async () => {
    installApi({ "/api/auth/logout": anonymous });
    renderApp();
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    await userEvent.click(screen.getByRole("button", { name: "Sair" }));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(screen.getByText("Sua sessão expirou. Entre novamente para continuar.")).toBeInTheDocument();
  });

  it("exibe atividades recebidas da API e substitui dados antigos por um erro de atualização", async () => {
    let failed = false;
    installApi({ "/api/auth/activities?limit=20": () => failed
      ? json({ code: "service_unavailable" }, 503)
      : json([{ id: "activity-1", action: "USER_LOGGED_IN", createdAt: "2026-10-09T12:00:00Z" }]),
    });
    renderApp();
    await screen.findByText("Entrada no sistema");
    expect(document.querySelector("time")).toHaveAttribute("datetime", "2026-10-09T12:00:00Z");
    failed = true;
    await userEvent.click(screen.getByRole("button", { name: "Atualizar atividade" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("temporariamente indisponível");
    expect(screen.queryByText("Entrada no sistema")).not.toBeInTheDocument();
  });
});

describe("Fundação integrada à autenticação", () => {
  it("mostra apenas informações confirmadas pela API e um estado vazio para atividades", async () => {
    installApi();
    renderApp();
    await screen.findByText("Serviços conectados");
    expect(screen.getByText("PostgreSQL 18.3")).toBeInTheDocument();
    expect(screen.getByText("Versão 2")).toBeInTheDocument();
    expect(screen.queryByText("Total de robôs")).not.toBeInTheDocument();
    expect(await screen.findByText("Robôs cadastrados")).toBeInTheDocument();
    expect(await screen.findByText("Nenhuma atividade registrada para sua conta.")).toBeInTheDocument();
  });

  it("mostra indisponibilidade e permite nova consulta do estado do sistema", async () => {
    let available = false;
    installApi({ "/api/system/status": () => {
      if (!available) return Promise.reject(new TypeError("offline"));
      return json(validStatus);
    } });
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível acessar o backend");
    expect(screen.getAllByText("Não verificado")).toHaveLength(3);
    available = true;
    await userEvent.click(screen.getByRole("button", { name: "Verificar agora" }));
    await screen.findByText("Serviços conectados");
  });

  it("não mantém estado operacional antigo após uma nova verificação falhar", async () => {
    let available = true;
    installApi({ "/api/system/status": () => {
      if (!available) return Promise.reject(new TypeError("offline"));
      return json(validStatus);
    } });
    renderApp();
    await screen.findByText("Serviços conectados");
    available = false;
    await userEvent.click(screen.getByRole("button", { name: "Verificar agora" }));
    await screen.findByRole("alert");
    expect(screen.queryByText("Operacional")).not.toBeInTheDocument();
    expect(screen.queryByText("PostgreSQL 18.3")).not.toBeInTheDocument();
  });

  it("alterna e persiste somente a preferência de tema", async () => {
    installApi();
    renderApp();
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    await userEvent.click(screen.getByRole("button", { name: "Ativar tema escuro" }));
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(localStorage.getItem("roboparts-theme")).toBe("dark");
    expect(localStorage.length).toBe(1);
    await userEvent.click(screen.getByRole("button", { name: "Ativar tema claro" }));
    expect(document.documentElement).toHaveAttribute("data-theme", "light");
  });

  it("navega para o diagnóstico e apresenta a resposta recebida", async () => {
    installApi();
    renderApp();
    await screen.findByText("Serviços conectados");
    const nav = screen.getByRole("navigation", { name: "Navegação principal" });
    await userEvent.click(within(nav).getByRole("link", { name: "Ambiente do sistema" }));
    expect(await screen.findByRole("heading", { name: "Uma base conectada." })).toBeInTheDocument();
    expect(screen.getByLabelText("Resposta JSON da API")).toHaveTextContent('"version": "18.3"');
    await waitFor(() => expect(document.title).toBe("RoboParts | Ambiente do sistema"));
  });

  it("retorna ao início a partir de uma rota inexistente", async () => {
    installApi();
    renderApp("/inexistente");
    await screen.findByRole("heading", { name: "Este caminho não existe." });
    await userEvent.click(screen.getByRole("link", { name: "Voltar ao início" }));
    await screen.findByRole("heading", { name: "Bem-vindo, Ana Silva." });
    await screen.findByText("Serviços conectados");
  });
});
