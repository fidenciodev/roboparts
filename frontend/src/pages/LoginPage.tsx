import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ArrowRight, CheckCircle2, LoaderCircle, LogIn } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { useAuth } from "../hooks/useAuth";
import { ApiError } from "../services/api";
import { focusFirstInvalid, validateLogin } from "../services/authValidation";
import { safeReturnPath } from "../routes/ProtectedRoute";

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const form = useRef<HTMLFormElement>(null);
  const alert = useRef<HTMLDivElement>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const returnPath = safeReturnPath(location.state?.from);
  const registered = location.state?.registered === true;
  useEffect(() => { document.title = "RoboParts | Entrar"; }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);
    const errors = validateLogin(email, password);
    setFields(errors);
    if (Object.keys(errors).length) { focusFirstInvalid(form.current, errors); return; }
    setPending(true);
    try {
      await auth.login(email.trim(), password);
      setPassword("");
      navigate(returnPath, { replace: true });
    } catch (cause) {
      setPassword("");
      setError(cause instanceof ApiError ? cause.message : "Não foi possível entrar. Tente novamente.");
      const errors = cause instanceof ApiError ? cause.fields : {};
      setFields(errors);
      requestAnimationFrame(() => { if (Object.keys(errors).length) focusFirstInvalid(form.current, errors); else alert.current?.focus(); });
    } finally { setPending(false); }
  }

  return <AuthLayout>
    <span className="auth-form-icon"><LogIn size={23} aria-hidden="true" /></span>
    <p className="eyebrow">BEM-VINDO DE VOLTA</p>
    <h1>Entre no seu espaço.</h1>
    <p className="auth-intro">Use sua conta de funcionário para continuar.</p>
    {registered && <div className="auth-feedback auth-feedback--success" role="status"><CheckCircle2 size={18} aria-hidden="true" /><p>Cadastro concluído. Entre com seu e-mail e senha.</p></div>}
    {auth.sessionExpired && <div className="auth-feedback" role="status"><p>Sua sessão expirou. Entre novamente para continuar.</p></div>}
    {(error || auth.error) && <div ref={alert} className="auth-feedback auth-feedback--error" role="alert" tabIndex={-1}><p>{error || auth.error}</p></div>}
    <form ref={form} noValidate onSubmit={(event) => void submit(event)} aria-busy={pending}>
      <fieldset disabled={pending}>
        <FormField id="login-email" name="email" label="E-mail" type="email" autoComplete="username" inputMode="email" value={email} maxLength={254} required error={fields.email} onChange={(event) => setEmail(event.target.value)} />
        <FormField id="login-password" name="password" label="Senha" type="password" autoComplete="current-password" value={password} maxLength={128} required error={fields.password} onChange={(event) => setPassword(event.target.value)} />
        <button type="submit" className="primary-button auth-submit" disabled={pending}>{pending ? <><LoaderCircle size={17} className="animate-spin" aria-hidden="true" /> Entrando…</> : <>Entrar <ArrowRight size={17} aria-hidden="true" /></>}</button>
      </fieldset>
    </form>
    <p className="auth-switch">Ainda não tem uma conta? <Link to="/cadastro" state={{ from: returnPath }}>Cadastrar com código</Link></p>
  </AuthLayout>;
}
