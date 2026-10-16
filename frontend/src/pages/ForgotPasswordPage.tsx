import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { KeyRound, LoaderCircle } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { ApiError } from "../services/api";
import { requestPasswordReset, resetPassword } from "../services/authService";
import { focusFirstInvalid, validateEmail, validatePasswordReset } from "../services/authValidation";
import { safeReturnPath } from "../routes/ProtectedRoute";

const sentNotice = "Se o e-mail estiver cadastrado, enviaremos um código para criar uma nova senha. Confira também a pasta de spam.";

export function ForgotPasswordPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const returnPath = safeReturnPath(location.state?.from);
  const [email, setEmail] = useState(typeof location.state?.email === "string" ? location.state.email : "");
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<"send" | "reset" | null>(null);
  const [wait, setWait] = useState(0);
  const lock = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const alert = useRef<HTMLDivElement>(null);
  const codeInput = useRef<HTMLInputElement>(null);

  useEffect(() => { document.title = "RoboParts | Recuperar senha"; }, []);
  useEffect(() => {
    const timer = window.setInterval(() => setWait(old => Math.max(0, old - 1)), 1000);
    return () => window.clearInterval(timer);
  }, []);

  function showError(cause: unknown) {
    setError(cause instanceof ApiError ? cause.message : "Não foi possível concluir. Tente novamente.");
    setFields(cause instanceof ApiError ? cause.fields : {});
    requestAnimationFrame(() => alert.current?.focus());
  }

  async function send() {
    if (lock.current || wait > 0) return;
    const errors = validateEmail(email);
    setError(null); setFields(errors);
    if (Object.keys(errors).length) { focusFirstInvalid(form.current, errors); return; }
    lock.current = true; setPending("send"); setNotice(null);
    try {
      await requestPasswordReset(email.trim());
      setEmail(email.trim()); setSent(true); setWait(60); setCode("");
      setPassword(""); setConfirmation(""); setNotice(sentNotice);
      requestAnimationFrame(() => codeInput.current?.focus());
    } catch (cause) { showError(cause); }
    finally { lock.current = false; setPending(null); }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!sent) { await send(); return; }
    if (lock.current) return;
    const errors = validatePasswordReset(email, code, password, confirmation);
    setError(null); setFields(errors);
    if (Object.keys(errors).length) { focusFirstInvalid(form.current, errors); return; }
    lock.current = true; setPending("reset"); setNotice(null);
    try {
      await resetPassword(email.trim(), code, password);
      setPassword(""); setConfirmation(""); setCode("");
      navigate("/entrar", { replace: true, state: { email: email.trim(), passwordReset: true, from: returnPath } });
    } catch (cause) {
      setPassword(""); setConfirmation(""); setCode(""); showError(cause);
    } finally { lock.current = false; setPending(null); }
  }

  function changeEmail() {
    setSent(false); setCode(""); setPassword(""); setConfirmation("");
    setError(null); setNotice(null); setFields({});
  }

  return <AuthLayout>
    <span className="auth-form-icon"><KeyRound size={23} aria-hidden="true" /></span>
    <p className="eyebrow">RECUPERAÇÃO DE ACESSO</p>
    <h1>{sent ? "Crie uma nova senha." : "Esqueceu sua senha?"}</h1>
    <p className="auth-intro">{sent
      ? <>Use o código recebido em <strong>{email}</strong>. Ele vale por 10 minutos e permite até 5 tentativas.</>
      : "Informe o e-mail da sua conta para solicitar um código de recuperação."}</p>
    {error && <div ref={alert} className="auth-feedback auth-feedback--error" role="alert" tabIndex={-1}><p>{error}</p></div>}
    {notice && <div className="auth-feedback auth-feedback--success" role="status"><p>{notice}</p></div>}
    <form ref={form} noValidate onSubmit={event => void submit(event)} aria-busy={pending !== null}>
      <fieldset disabled={pending !== null}>
        {!sent ? <FormField id="recovery-email" name="email" label="E-mail" type="email" autoComplete="email" inputMode="email" maxLength={254} value={email} required error={fields.email} onChange={event => setEmail(event.target.value)} /> : <>
          <FormField ref={codeInput} id="recovery-code" name="code" label="Código de recuperação" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code} required error={fields.code} onChange={event => setCode(event.target.value.replace(/[^0-9]/g, ""))} />
          <FormField id="recovery-password" name="password" label="Nova senha" type="password" autoComplete="new-password" maxLength={128} value={password} required hint="Use de 12 a 128 caracteres." error={fields.password} onChange={event => setPassword(event.target.value)} />
          <FormField id="recovery-confirmation" name="confirmation" label="Confirmar nova senha" type="password" autoComplete="new-password" maxLength={128} value={confirmation} required error={fields.confirmation} onChange={event => setConfirmation(event.target.value)} />
          <p className="auth-intro">Após a troca, entre com a nova senha. As sessões anteriores perderão o acesso.</p>
        </>}
        <button type="submit" className="primary-button auth-submit" disabled={pending !== null || (!sent && wait > 0)}>
          {pending ? <><LoaderCircle size={17} className="animate-spin" aria-hidden="true" /> {pending === "send" ? "Enviando…" : "Salvando…"}</>
            : sent ? "Salvar nova senha" : wait > 0 ? `Enviar em ${wait}s` : "Enviar código"}
        </button>
      </fieldset>
    </form>
    {sent && <>
      <p className="auth-switch"><button className="secondary-button" type="button" disabled={pending !== null || wait > 0} onClick={() => void send()}>{wait > 0 ? `Reenviar em ${wait}s` : "Reenviar código"}</button></p>
      <p className="auth-switch"><button className="secondary-button" type="button" disabled={pending !== null} onClick={changeEmail}>Usar outro e-mail</button></p>
    </>}
    <p className="auth-switch"><Link to="/entrar" state={{ email, from: returnPath }}>Voltar para o login</Link></p>
  </AuthLayout>;
}
