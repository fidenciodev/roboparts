import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ArrowRight, KeyRound, LoaderCircle, UserPlus } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { register } from "../services/authService";
import { ApiError } from "../services/api";
import { focusFirstInvalid, validateRegistration } from "../services/authValidation";
import { safeReturnPath } from "../routes/ProtectedRoute";
import type { RegistrationInput } from "../types/auth";

export function RegisterPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const form = useRef<HTMLFormElement>(null);
  const alert = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState<RegistrationInput>({ name: "", email: "", password: "", accessCode: "" });
  const [confirmation, setConfirmation] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const returnPath = safeReturnPath(location.state?.from);
  useEffect(() => { document.title = "RoboParts | Cadastro"; }, []);

  const change = (field: keyof RegistrationInput, value: string) => setInput((current) => ({ ...current, [field]: value }));
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);
    const errors = validateRegistration(input, confirmation);
    setFields(errors);
    if (Object.keys(errors).length) { focusFirstInvalid(form.current, errors); return; }
    setPending(true);
    try {
      await register({ ...input, name: input.name.trim(), email: input.email.trim() });
      setInput({ name: "", email: "", password: "", accessCode: "" });
      setConfirmation("");
      navigate("/entrar", { replace: true, state: { registered: true, from: returnPath } });
    } catch (cause) {
      setInput((current) => ({ ...current, password: "", accessCode: "" }));
      setConfirmation("");
      setError(cause instanceof ApiError ? cause.message : "Não foi possível cadastrar sua conta. Tente novamente.");
      const errors = cause instanceof ApiError ? cause.fields : {};
      setFields(errors);
      requestAnimationFrame(() => { if (Object.keys(errors).length) focusFirstInvalid(form.current, errors); else alert.current?.focus(); });
    } finally { setPending(false); }
  }

  return <AuthLayout>
    <span className="auth-form-icon"><UserPlus size={23} aria-hidden="true" /></span>
    <p className="eyebrow">FAÇA PARTE DA EQUIPE</p>
    <h1>Seu acesso começa aqui.</h1>
    <p className="auth-intro">Crie sua conta com o código fornecido pela sua equipe.</p>
    {error && <div ref={alert} className="auth-feedback auth-feedback--error" role="alert" tabIndex={-1}><p>{error}</p></div>}
    <form ref={form} noValidate onSubmit={(event) => void submit(event)} aria-busy={pending}>
      <fieldset disabled={pending}>
        <FormField id="register-name" name="name" label="Nome" autoComplete="name" value={input.name} maxLength={100} required error={fields.name} onChange={(event) => change("name", event.target.value)} />
        <FormField id="register-email" name="email" label="E-mail" type="email" autoComplete="email" inputMode="email" value={input.email} maxLength={254} required error={fields.email} onChange={(event) => change("email", event.target.value)} />
        <div className="form-row">
          <FormField id="register-password" name="password" label="Senha" type="password" autoComplete="new-password" value={input.password} maxLength={128} required hint="Use de 12 a 128 caracteres." error={fields.password} onChange={(event) => change("password", event.target.value)} />
          <FormField id="register-confirmation" name="confirmation" label="Confirmar senha" type="password" autoComplete="new-password" value={confirmation} maxLength={128} required error={fields.confirmation} onChange={(event) => setConfirmation(event.target.value)} />
        </div>
        <FormField id="register-code" name="accessCode" label="Código de acesso" type="password" autoComplete="off" value={input.accessCode} maxLength={256} required hint="Solicite o código à sua equipe. Ele não é sua senha." error={fields.accessCode} onChange={(event) => change("accessCode", event.target.value)} />
        <button type="submit" className="primary-button auth-submit" disabled={pending}>{pending ? <><LoaderCircle size={17} className="animate-spin" aria-hidden="true" /> Cadastrando…</> : <>Criar minha conta <ArrowRight size={17} aria-hidden="true" /></>}</button>
      </fieldset>
    </form>
    <p className="auth-switch">Já tem uma conta? <Link to="/entrar" state={{ from: returnPath }}>Entrar</Link></p>
    <div className="auth-code-note"><KeyRound size={15} aria-hidden="true" /><p>O cadastro é exclusivo para funcionários com código de acesso válido.</p></div>
  </AuthLayout>;
}
