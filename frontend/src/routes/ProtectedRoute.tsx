import { Navigate, Outlet, useLocation } from "react-router";
import { RefreshCw, ShieldCheck } from "lucide-react";
import { useAuth } from "../hooks/useAuth";

export function SessionLoading() {
  return <div className="session-screen" role="status" aria-live="polite">
    <ShieldCheck size={32} aria-hidden="true" />
    <h1>Verificando seu acesso</h1>
    <p>Aguarde enquanto recuperamos sua sessão segura.</p>
  </div>;
}

// Aceita apenas caminhos do próprio aplicativo e preserva busca e fragmento.
export function safeReturnPath(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") ||
    /[\\\u0000-\u0020]/.test(value)) return "/";
  try {
    const decoded = decodeURIComponent(value);
    if (decoded.startsWith("//") || /[\\\u0000-\u0020]/.test(decoded)) return "/";
    const url = new URL(value, "https://roboparts.local");
    if (url.origin !== "https://roboparts.local" || ["/entrar", "/cadastro"].includes(url.pathname)) return "/";
    return `${url.pathname}${url.search}${url.hash}`;
  } catch { return "/"; }
}

export function ProtectedRoute() {
  const auth = useAuth();
  const location = useLocation();
  if (auth.status === "loading") return <SessionLoading />;
  if (auth.status === "unavailable") return <div className="session-screen">
    <ShieldCheck size={32} aria-hidden="true" />
    <h1>Não foi possível verificar seu acesso</h1>
    <p role="alert">{auth.error}</p>
    <button className="primary-button" type="button" onClick={auth.retrySession}>
      <RefreshCw size={16} aria-hidden="true" /> Tentar novamente
    </button>
  </div>;
  if (!auth.user) return <Navigate to="/entrar" replace state={{ from: `${location.pathname}${location.search}${location.hash}` }} />;
  return <Outlet />;
}

export function PublicRoute() {
  const auth = useAuth();
  const location = useLocation();
  if (auth.status === "loading") return <SessionLoading />;
  if (auth.user) return <Navigate replace to={safeReturnPath(location.state?.from)} />;
  return <Outlet />;
}
