import { Bot, ClipboardCheck, Moon, ShieldCheck, Sun } from "lucide-react";
import type { PropsWithChildren } from "react";
import { Link } from "react-router";
import { useTheme } from "../hooks/useTheme";

export function AuthLayout({ children }: PropsWithChildren) {
  const { theme, toggleTheme } = useTheme();
  return <main className="auth-shell">
    <section className="auth-story" aria-label="RoboParts">
      <Link to="/entrar" className="brand" aria-label="RoboParts — entrar">
        <span className="brand-icon"><Bot size={26} aria-hidden="true" /></span>
        <span>Robo<span className="brand-accent">Parts</span><small>CONFERIR. RETIRAR. REGISTRAR.</small></span>
      </Link>
      <div className="auth-story-copy">
        <span className="banner-label"><ShieldCheck size={15} aria-hidden="true" /> ACESSO DA EQUIPE</span>
        <h2>Cada componente conta.<br />Cada pessoa também.</h2>
        <p>Seu espaço para conferir robôs e componentes, com clareza sobre cada retirada e seu responsável.</p>
        <div className="auth-story-note"><ClipboardCheck size={21} aria-hidden="true" /><span>Uma conferência cuidadosa começa com uma equipe conectada.</span></div>
      </div>
      <p className="auth-story-footer">Controle de retirada de robôs e componentes</p>
    </section>
    <section className="auth-content">
      <button type="button" onClick={toggleTheme} className="icon-button auth-theme" aria-label={theme === "light" ? "Ativar tema escuro" : "Ativar tema claro"}>
        {theme === "light" ? <Moon size={19} aria-hidden="true" /> : <Sun size={19} aria-hidden="true" />}
      </button>
      <div className="auth-card">{children}</div>
      <p className="auth-footer"><ShieldCheck size={14} aria-hidden="true" /> Acesso interno · Cadastro por código de acesso</p>
    </section>
  </main>;
}
