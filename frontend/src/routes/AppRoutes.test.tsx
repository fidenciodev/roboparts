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
const verification = { verificationId:"8f5d4544-2e9a-44ec-bf5e-ab415da134ad",email:currentUser.email,expiresInSeconds:600,retryAfterSeconds:0 };
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
    "/api/auth/register": () => json(verification, 201),
    "/api/auth/verify-email": () => json(currentUser),
    "/api/auth/resend-verification": () => json({...verification,retryAfterSeconds:60}),
    "/api/auth/forgot-password": () => json({ message: "Se o e-mail estiver cadastrado, enviaremos um código." }, 202),
    "/api/auth/reset-password": () => new Response(null, { status: 204 }),
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

describe("Autenticador e gerenciamento de segurança", () => {
  it("só abre o espaço privado depois da confirmação do segundo fator", async () => {
    let resolveFactor!: (response:Response)=>void;
    const fetchMock=installApi({
      "/api/auth/me":anonymous,
      "/api/auth/login":()=>json({mfaRequired:true,expiresInSeconds:300},202),
      "/api/auth/mfa-login":()=>new Promise<Response>(resolve=>{resolveFactor=resolve;}),
    });
    renderApp("/entrar");await screen.findByLabelText("Senha");await fillLogin();
    await userEvent.click(screen.getByRole("button",{name:"Entrar"}));
    await screen.findByRole("heading",{name:"Confirme seu acesso."});
    expect(screen.queryByLabelText("Funcionário conectado")).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Código do autenticador"),"123456");
    await userEvent.click(screen.getByRole("button",{name:"Confirmar acesso"}));
    expect(screen.getByRole("button",{name:/Confirmando/})).toBeDisabled();
    expect(screen.queryByRole("heading",{name:"Bem-vindo, Ana Silva."})).not.toBeInTheDocument();
    await act(async()=>resolveFactor(json(currentUser)));
    await screen.findByRole("heading",{name:"Bem-vindo, Ana Silva."});
    expect(fetchMock.mock.calls.filter(([path])=>path==="/api/auth/mfa-login")).toHaveLength(1);
    expectOnlyThemeStored();
  });
  it("aceita código de recuperação e explica quando a etapa de login expirou", async()=>{
    const fetchMock=installApi({"/api/auth/me":anonymous,"/api/auth/mfa-login":()=>json({code:"mfa_challenge_expired"},400)});
    renderApp("/segundo-fator");await screen.findByRole("heading",{name:"Confirme seu acesso."});
    await userEvent.click(screen.getByRole("button",{name:"Usar código de recuperação"}));
    await userEvent.type(screen.getByLabelText("Código de recuperação"),"aaaa-bbbb-cccc-dddd-eeee-ffff-0000-1111");
    await userEvent.click(screen.getByRole("button",{name:"Confirmar acesso"}));
    expect(await screen.findByRole("alert")).toHaveTextContent("A confirmação expirou");
    expect(screen.getByLabelText("Código de recuperação")).toHaveValue("");
    expect(fetchMock.mock.calls.filter(([path])=>path==="/api/auth/mfa-login")).toHaveLength(1);
  });
  it("configura o autenticador e mostra códigos de recuperação apenas até confirmar que foram guardados",async()=>{
    const codes=Array.from({length:8},(_,index)=>`aaaa-bbbb-cccc-dddd-eeee-ffff-0000-000${index}`);
    const fetchMock=installApi({
      "/api/security":()=>json({manager:false,mfa:{enabled:false,remainingRecoveryCodes:0}}),
      "/api/security/mfa/setup":()=>json({secret:"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",uri:"otpauth://totp/RoboParts",expiresAt:"2026-10-16T15:00:00Z"}),
      "/api/security/mfa/confirm":()=>json({recoveryCodes:codes}),
    });
    renderApp("/seguranca");await screen.findByLabelText("Senha atual");
    expect(screen.queryByRole("heading",{name:/Convites individuais/})).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Senha atual"),"current-password-123");
    await userEvent.click(screen.getByRole("button",{name:"Configurar autenticador"}));
    expect(await screen.findByLabelText("Chave de configuração")).toHaveValue("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567");
    await userEvent.type(screen.getByLabelText("Código do autenticador"),"123456");
    await userEvent.click(screen.getByRole("button",{name:"Ativar duas etapas"}));
    expect(await screen.findByLabelText("Seus códigos de recuperação")).toHaveValue(codes.join("\n"));
    expect(screen.queryByLabelText("Chave de configuração")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button",{name:"Já guardei os códigos"}));
    expect(screen.queryByLabelText("Seus códigos de recuperação")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Senha atual")).toHaveValue("");
    expect(fetchMock.mock.calls.some(([path])=>String(path).includes("/employees"))).toBe(false);
    expectOnlyThemeStored();
  });
  it("mostra convites individuais e bloqueio de funcionários somente ao responsável",async()=>{
    const token="A".repeat(43);let blocked=false;
    const fetchMock=installApi({
      "/api/security":()=>json({manager:true,mfa:{enabled:false,remainingRecoveryCodes:0}}),
      "/api/security/employees":()=>json([{...currentUser,active:true,verified:true},{id:"other-id",name:"Outro funcionário",email:"other@example.com",active:!blocked,verified:true}]),
      "/api/security/invitations":options=>options.method==="POST"?json({id:"invite-id",email:"new@example.com",token,expiresAt:"2030-01-01T00:00:00Z"},201):json([]),
      "/api/security/events":()=>json({summary:[],recent:[]}),
      "/api/security/employees/other-id":()=>{blocked=true;return new Response(null,{status:204});},
    });
    renderApp("/seguranca");await screen.findByRole("button",{name:"Sua conta"});
    expect(screen.getByRole("button",{name:"Sua conta"})).toBeDisabled();
    await userEvent.type(screen.getByLabelText("E-mail do novo funcionário"),"new@example.com");
    await userEvent.click(screen.getByRole("button",{name:"Gerar convite"}));
    expect(await screen.findByLabelText("Código do convite")).toHaveValue(token);
    await userEvent.click(screen.getByRole("button",{name:"Já copiei o convite"}));
    expect(screen.queryByLabelText("Código do convite")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button",{name:"Bloquear acesso"}));
    await screen.findByRole("button",{name:"Reativar acesso"});
    const update=fetchMock.mock.calls.find(([path])=>path==="/api/security/employees/other-id");
    expect(JSON.parse(update?.[1].body as string)).toEqual({active:false});expectOnlyThemeStored();
  });
});

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

  it.each(["https://outside.example", "//outside.example", "/%2foutside.example", "/\\outside.example", "/entrar", "/cadastro", "/esqueci-senha", "/%5coutside.example", "/%00outside.example"])(
    "descarta destinos de retorno inválidos (%s)", (path) => expect(safeReturnPath(path)).toBe("/"),
  );
  it("preserva busca e fragmento de um destino interno válido", () => {
    expect(safeReturnPath("/sistema?local=1#diagnostico")).toBe("/sistema?local=1#diagnostico");
  });
});

describe("Formulários de acesso", () => {
  it("recupera a senha pelo link de login, exige confirmação e preserva o destino original", async () => {
    const mock = installApi({ "/api/auth/me": anonymous });
    renderApp("/sistema?local=1#diagnostico");
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    await userEvent.type(screen.getByLabelText("E-mail"), currentUser.email);
    await userEvent.click(screen.getByRole("link", { name: "Esqueci minha senha" }));
    await screen.findByRole("heading", { name: "Esqueceu sua senha?" });
    expect(screen.getByLabelText("E-mail")).toHaveValue(currentUser.email);
    await userEvent.click(screen.getByRole("button", { name: "Enviar código" }));
    await screen.findByRole("heading", { name: "Crie uma nova senha." });
    expect(screen.getByRole("status")).toHaveTextContent("Se o e-mail estiver cadastrado");
    expect(screen.getByRole("button", { name: /Reenviar em/ })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Código de recuperação"), "012345");
    await userEvent.type(screen.getByLabelText("Nova senha"), "new-valid-password-123");
    await userEvent.type(screen.getByLabelText("Confirmar nova senha"), "different-password");
    await userEvent.click(screen.getByRole("button", { name: "Salvar nova senha" }));
    expect(screen.getByLabelText("Confirmar nova senha")).toHaveFocus();
    expect(mock.mock.calls.some(([path]) => path === "/api/auth/reset-password")).toBe(false);
    await userEvent.clear(screen.getByLabelText("Confirmar nova senha"));
    await userEvent.type(screen.getByLabelText("Confirmar nova senha"), "new-valid-password-123");
    await userEvent.click(screen.getByRole("button", { name: "Salvar nova senha" }));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(screen.getByRole("status")).toHaveTextContent("Senha alterada. Entre com sua nova senha.");
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    const resets = mock.mock.calls.filter(([path]) => path === "/api/auth/reset-password");
    expect(resets).toHaveLength(1);
    expect(JSON.parse(resets[0][1].body)).toEqual({ email: currentUser.email, code: "012345", password: "new-valid-password-123" });
    expect(mock.mock.calls.some(([path]) => path === "/api/auth/login")).toBe(false);
    await userEvent.type(screen.getByLabelText("Senha"), "new-valid-password-123");
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    await screen.findByRole("heading", { name: "Uma base conectada." });
    expect(screen.getByTestId("location")).toHaveTextContent("/sistema?local=1#diagnostico");
    expectOnlyThemeStored();
  });

  it("valida o e-mail e impede pedidos duplicados enquanto aguarda o envio", async () => {
    let resolveRequest!: (response: Response) => void;
    const mock = installApi({ "/api/auth/me": anonymous,
      "/api/auth/forgot-password": () => new Promise<Response>(resolve => { resolveRequest = resolve; }),
    });
    renderApp("/esqueci-senha");
    await screen.findByRole("heading", { name: "Esqueceu sua senha?" });
    await userEvent.click(screen.getByRole("button", { name: "Enviar código" }));
    expect(screen.getByLabelText("E-mail")).toHaveFocus();
    expect(mock.mock.calls.some(([path]) => path === "/api/auth/forgot-password")).toBe(false);
    await userEvent.type(screen.getByLabelText("E-mail"), "missing@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Enviar código" }));
    expect(screen.getByRole("button", { name: "Enviando…" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Enviando…" }));
    expect(mock.mock.calls.filter(([path]) => path === "/api/auth/forgot-password")).toHaveLength(1);
    await act(async () => resolveRequest(json({ message: "generic" }, 202)));
    expect(await screen.findByRole("status")).toHaveTextContent("Se o e-mail estiver cadastrado");
    expectOnlyThemeStored();
  });

  it("código rejeitado mantém o fluxo e limpa o código e as duas senhas", async () => {
    installApi({ "/api/auth/me": anonymous, "/api/auth/reset-password": () => json({ code: "password_reset_invalid" }, 400) });
    renderApp("/esqueci-senha");
    await screen.findByRole("heading", { name: "Esqueceu sua senha?" });
    await userEvent.type(screen.getByLabelText("E-mail"), currentUser.email);
    await userEvent.click(screen.getByRole("button", { name: "Enviar código" }));
    await screen.findByRole("heading", { name: "Crie uma nova senha." });
    await userEvent.type(screen.getByLabelText("Código de recuperação"), "012345");
    await userEvent.type(screen.getByLabelText("Nova senha"), "new-valid-password-123");
    await userEvent.type(screen.getByLabelText("Confirmar nova senha"), "new-valid-password-123");
    await userEvent.click(screen.getByRole("button", { name: "Salvar nova senha" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Código inválido");
    expect(screen.getByLabelText("Código de recuperação")).toHaveValue("");
    expect(screen.getByLabelText("Nova senha")).toHaveValue("");
    expect(screen.getByLabelText("Confirmar nova senha")).toHaveValue("");
    expect(screen.getByRole("heading", { name: "Crie uma nova senha." })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Usar outro e-mail" }));
    expect(screen.getByLabelText("E-mail")).toHaveValue(currentUser.email);
    expectOnlyThemeStored();
  });

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

  it("exige confirmação da senha e do e-mail antes de permitir o login", async () => {
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
    await screen.findByRole("heading", { name: "Confirme seu e-mail." });
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Código de acesso")).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Código de confirmação"),"012345");
    await userEvent.click(screen.getByRole("button",{name:"Confirmar e-mail"}));
    await screen.findByRole("heading", { name: "Entre no seu espaço." });
    expect(screen.getByRole("status")).toHaveTextContent("E-mail confirmado. Entre com sua senha para continuar.");
    expect(screen.getByLabelText("E-mail")).toHaveValue(currentUser.email);
    const registrations = fetchMock.mock.calls.filter(([path]) => path === "/api/auth/register");
    expect(registrations).toHaveLength(1);
    expect(JSON.parse(registrations[0][1].body)).toEqual({
      name: "Ana Silva", email: currentUser.email, password: "valid-password-123", accessCode: "team-access-code",
    });
    expect(fetchMock.mock.calls.some(([path]) => path === "/api/auth/login")).toBe(false);
    expectOnlyThemeStored();
  });

  it("envia contas existentes à confirmação e só autentica depois de verificar o código",async()=>{
    let verified=false;
    const mock=installApi({"/api/auth/me":anonymous,"/api/auth/login":()=>verified?json(currentUser):json(verification,202),
      "/api/auth/verify-email":()=>{verified=true;return json(currentUser);}});
    renderApp("/sistema?local=1#diagnostico");
    await screen.findByRole("heading",{name:"Entre no seu espaço."});await fillLogin();
    await userEvent.click(screen.getByRole("button",{name:"Entrar"}));
    await screen.findByRole("heading",{name:"Confirme seu e-mail."});
    expect(screen.queryByLabelText("Funcionário conectado")).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Código de confirmação"),"012345");
    await userEvent.click(screen.getByRole("button",{name:"Confirmar e-mail"}));
    await screen.findByRole("heading",{name:"Entre no seu espaço."});
    await userEvent.type(screen.getByLabelText("Senha"),"valid-password-123");
    await userEvent.click(screen.getByRole("button",{name:"Entrar"}));
    await screen.findByRole("heading",{name:"Uma base conectada."});
    expect(screen.getByTestId("location")).toHaveTextContent("/sistema?local=1#diagnostico");
    expect(mock.mock.calls.filter(([path])=>path==="/api/auth/login")).toHaveLength(2);
    expectOnlyThemeStored();
  });

  it("valida seis dígitos, permite corrigir código rejeitado e controla o reenvio",async()=>{
    const mock=installApi({"/api/auth/me":anonymous,"/api/auth/login":()=>json(verification,202),
      "/api/auth/verify-email":()=>json({code:"verification_invalid"},400)});
    renderApp("/entrar");await screen.findByRole("heading",{name:"Entre no seu espaço."});await fillLogin();
    await userEvent.click(screen.getByRole("button",{name:"Entrar"}));
    await screen.findByRole("heading",{name:"Confirme seu e-mail."});
    await userEvent.click(screen.getByRole("button",{name:"Confirmar e-mail"}));
    expect(screen.getByLabelText("Código de confirmação")).toHaveFocus();
    expect(mock.mock.calls.some(([path])=>path==="/api/auth/verify-email")).toBe(false);
    await userEvent.type(screen.getByLabelText("Código de confirmação"),"123456");
    await userEvent.click(screen.getByRole("button",{name:"Confirmar e-mail"}));
    expect(await screen.findByRole("alert")).toHaveTextContent("Código inválido");
    expect(screen.getByLabelText("Código de confirmação")).toHaveValue("");
    await userEvent.click(screen.getByRole("button",{name:"Reenviar código"}));
    expect(await screen.findByRole("status")).toHaveTextContent("Use o código do e-mail mais recente");
    expect(screen.getByRole("button",{name:/Reenviar em/})).toBeDisabled();
    expect(mock.mock.calls.filter(([path])=>path==="/api/auth/resend-verification")).toHaveLength(1);
    expectOnlyThemeStored();
  });

  it("não tenta confirmar um e-mail sem um desafio iniciado",async()=>{
    const mock=installApi({"/api/auth/me":anonymous});renderApp("/confirmar-email");
    await screen.findByRole("heading",{name:"Confirme seu e-mail."});
    expect(screen.queryByLabelText("Código de confirmação")).not.toBeInTheDocument();
    expect(mock.mock.calls.map(([path])=>path)).toEqual(["/api/auth/me"]);
    expect(screen.getByRole("link",{name:"Voltar para o login"})).toHaveAttribute("href","/entrar");
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
