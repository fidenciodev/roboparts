import {
  Activity,
  ArrowUpRight,
  Bot,
  ChevronRight,
  CircleDot,
  ClipboardCheck,
  History,
  LayoutDashboard,
  LoaderCircle,
  LogOut,
  Moon,
  Sun,
  UserRound,
} from "lucide-react";
import { NavLink, Outlet, useLocation } from "react-router";
import { useTheme } from "../hooks/useTheme";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "../hooks/useAuth";
import { ApiError } from "../services/api";

export function AppLayout() {
  const { theme, toggleTheme } = useTheme();
  const auth = useAuth();
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const logoutAlert = useRef<HTMLDivElement>(null);
  const location = useLocation();
  const title =
    location.pathname === "/sistema"
      ? "Ambiente do sistema"
      : location.pathname === "/"
        ? "Visão geral"
        : location.pathname.startsWith("/robos") ? "Robôs e componentes"
        : location.pathname.startsWith("/checklists") ? "Retiradas e devoluções"
        : location.pathname === "/historico" ? "Histórico da equipe"
        : "Página não encontrada";

  useEffect(() => {
    document.title = `RoboParts | ${title}`;
  }, [title]);

  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true);
    setLogoutError(null);
    try { await auth.logout(); }
    catch (cause) {
      setLogoutError(cause instanceof ApiError ? cause.message : "Não foi possível sair. Tente novamente.");
      requestAnimationFrame(() => logoutAlert.current?.focus());
    } finally { setLoggingOut(false); }
  }

  return (
    <div className="app-shell">
      <a href="#main-content" className="skip-link">
        Ir para o conteúdo
      </a>
      <aside className="sidebar">
        <NavLink to="/" className="brand" aria-label="RoboParts — início">
          <span className="brand-icon">
            <Bot aria-hidden="true" size={25} />
          </span>
          <span>
            Robo<span className="brand-accent">Parts</span>
            <small>CONFERIR. RETIRAR. REGISTRAR.</small>
          </span>
        </NavLink>
        <div className="sidebar-navigation">
          <p className="sidebar-caption">ESPAÇO DE TRABALHO</p>
          <nav
            aria-label="Navegação principal"
            className="flex flex-col gap-1.5"
          >
            <NavLink
              end
              to="/"
              className={({ isActive }) =>
                `nav-link ${isActive ? "nav-link--active" : ""}`
              }
            >
              <LayoutDashboard size={19} aria-hidden="true" />
              <span>Visão geral</span>
              <ChevronRight
                size={15}
                className="nav-chevron"
                aria-hidden="true"
              />
            </NavLink>
            <NavLink
              to="/sistema"
              className={({ isActive }) =>
                `nav-link ${isActive ? "nav-link--active" : ""}`
              }
            >
              <Activity size={19} aria-hidden="true" />
              <span>Ambiente do sistema</span>
              <ChevronRight
                size={15}
                className="nav-chevron"
                aria-hidden="true"
              />
            </NavLink>
            <NavLink to="/robos" className={({isActive}) => `nav-link ${isActive?"nav-link--active":""}`}><Bot size={19}/><span>Robôs e componentes</span></NavLink>
            <NavLink to="/checklists" className={({isActive}) => `nav-link ${isActive?"nav-link--active":""}`}><ClipboardCheck size={19}/><span>Retiradas e devoluções</span></NavLink>
            <NavLink to="/historico" className={({isActive}) => `nav-link ${isActive?"nav-link--active":""}`}><History size={19}/><span>Histórico da equipe</span></NavLink>
          </nav>
        </div>
        <div className="account-panel" aria-label="Funcionário conectado">
          <UserRound size={19} aria-hidden="true" />
          <div><strong>{auth.user?.name}</strong><span>{auth.user?.email}</span></div>
        </div>
        <div className="sidebar-footer">
          <div className="flex items-center gap-2">
            <CircleDot size={16} />
            <span>Desenvolvimento local</span>
          </div>
          <p>Retiradas e conferências da equipe</p>
        </div>
      </aside>
      <div className="app-content">
        <header className="topbar">
          <div className="flex items-center gap-2 text-sm">
            <span className="topbar-root">Meu espaço</span>
            <ChevronRight size={14} aria-hidden="true" />
            <span className="font-medium">{title}</span>
          </div>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={toggleTheme}
              className="icon-button"
              aria-label={
                theme === "light" ? "Ativar tema escuro" : "Ativar tema claro"
              }
              title={
                theme === "light" ? "Ativar tema escuro" : "Ativar tema claro"
              }
            >
              {theme === "light" ? <Moon size={19} /> : <Sun size={19} />}
            </button>
            <button type="button" className="secondary-button logout-button" disabled={loggingOut} onClick={() => void logout()}>
              {loggingOut ? <LoaderCircle size={16} className="animate-spin" aria-hidden="true" /> : <LogOut size={16} aria-hidden="true" />}
              {loggingOut ? "Saindo…" : "Sair"}
            </button>
          </div>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}>
          {logoutError && <div ref={logoutAlert} className="auth-feedback auth-feedback--error logout-error" role="alert" tabIndex={-1}><p>{logoutError}</p></div>}
          <Outlet />
        </main>
        <footer className="page-footer">
          <span>
            RoboParts <span className="footer-dot">·</span> Controle de retirada
            de robôs e componentes
          </span>
          <NavLink to="/sistema">
            Ver ambiente <ArrowUpRight size={13} aria-hidden="true" />
          </NavLink>
        </footer>
      </div>
    </div>
  );
}
