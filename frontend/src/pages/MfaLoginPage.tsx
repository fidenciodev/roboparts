import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { ShieldCheck, LoaderCircle } from "lucide-react";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { useAuth } from "../hooks/useAuth";
import { ApiError } from "../services/api";
import { safeReturnPath } from "../routes/ProtectedRoute";

export function MfaLoginPage() {
  const auth=useAuth(), location=useLocation(), navigate=useNavigate();
  const [code,setCode]=useState("");const [recovery,setRecovery]=useState(false);
  const [pending,setPending]=useState(false);const [error,setError]=useState<string|null>(null);
  const lock=useRef(false);const input=useRef<HTMLInputElement>(null);
  useEffect(()=>{document.title="RoboParts | Confirmar acesso";},[]);
  async function submit(event:FormEvent) {
    event.preventDefault();if(lock.current)return;
    const normalized=code.replace(/[- ]/g,"");
    if(recovery?!/^[a-f0-9]{32}$/i.test(normalized):!/^[0-9]{6}$/.test(code)) {
      setError(recovery?"Informe um dos códigos de recuperação que você guardou.":"Informe os 6 números do seu aplicativo autenticador.");input.current?.focus();return;
    }
    lock.current=true;setPending(true);setError(null);
    try { await auth.loginMfa(code);setCode("");navigate(safeReturnPath(location.state?.from),{replace:true}); }
    catch(cause) {setCode("");setError(cause instanceof ApiError?cause.message:"Não foi possível confirmar o acesso.");}
    finally {lock.current=false;setPending(false);}
  }
  return <AuthLayout>
    <span className="auth-form-icon"><ShieldCheck aria-hidden="true"/></span>
    <p className="eyebrow">AUTENTICAÇÃO EM DUAS ETAPAS</p><h1>Confirme seu acesso.</h1>
    <p className="auth-intro">{recovery?"Use um código de recuperação ainda não utilizado.":"Abra seu aplicativo autenticador e informe o código de 6 números do RoboParts. Esta etapa vale por 5 minutos."}</p>
    {error&&<div className="auth-feedback auth-feedback--error" role="alert"><p>{error}</p></div>}
    <form noValidate onSubmit={event=>void submit(event)} aria-busy={pending}><fieldset disabled={pending}>
      <FormField ref={input} id="mfa-login-code" name="code" label={recovery?"Código de recuperação":"Código do autenticador"} value={code} autoComplete="one-time-code" inputMode={recovery?"text":"numeric"} maxLength={recovery?80:6} required onChange={event=>setCode(event.target.value)}/>
      <button type="submit" className="primary-button auth-submit">{pending?<><LoaderCircle size={17} className="animate-spin"/> Confirmando…</>:"Confirmar acesso"}</button>
    </fieldset></form>
    <p className="auth-switch"><button type="button" className="secondary-button" disabled={pending} onClick={()=>{setRecovery(!recovery);setCode("");setError(null);}}>{recovery?"Usar aplicativo autenticador":"Usar código de recuperação"}</button></p>
    <p className="auth-switch"><Link to="/entrar" state={{email:location.state?.email,from:safeReturnPath(location.state?.from)}}>Voltar para o login</Link></p>
  </AuthLayout>;
}
