import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { LoaderCircle, MailCheck } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { ApiError } from "../services/api";
import { isEmailVerification, resendVerification, verifyEmail } from "../services/authService";
import { safeReturnPath } from "../routes/ProtectedRoute";
import type { EmailVerification } from "../types/auth";

export function VerifyEmailPage() {
  const location=useLocation();const navigate=useNavigate();
  const [verification,setVerification]=useState<EmailVerification|null>(()=>isEmailVerification(location.state?.verification)?location.state.verification:null);
  const [code,setCode]=useState("");const [fieldError,setFieldError]=useState<string>();
  const [error,setError]=useState<string|null>(null);const [notice,setNotice]=useState<string|null>(null);
  const [pending,setPending]=useState<"verify"|"resend"|null>(null);
  const [wait,setWait]=useState(verification?.retryAfterSeconds??0);
  const lock=useRef(false);const input=useRef<HTMLInputElement>(null);
  const returnPath=safeReturnPath(location.state?.from);
  useEffect(()=>{document.title="RoboParts | Confirmar e-mail";},[]);
  useEffect(()=>{const timer=window.setInterval(()=>setWait(old=>Math.max(0,old-1)),1000);return ()=>window.clearInterval(timer);},[]);
  async function submit(event:FormEvent) {
    event.preventDefault();if(!verification||lock.current)return;
    setError(null);setNotice(null);
    if(!/^[0-9]{6}$/.test(code)){setFieldError("Informe os 6 dígitos enviados ao seu e-mail.");input.current?.focus();return;}
    lock.current=true;setPending("verify");setFieldError(undefined);
    try {
      const user=await verifyEmail(verification.verificationId,code);
      setCode("");navigate("/entrar",{replace:true,state:{emailVerified:true,email:user.email,from:returnPath}});
    } catch(cause) {
      setError(cause instanceof ApiError?cause.message:"Não foi possível confirmar o e-mail. Tente novamente.");
      setCode("");input.current?.focus();
    } finally {lock.current=false;setPending(null);}
  }
  async function resend() {
    if(!verification||lock.current||wait>0)return;
    lock.current=true;setPending("resend");setError(null);setNotice(null);
    try {
      const result=await resendVerification(verification.verificationId);
      setVerification(result);setWait(result.retryAfterSeconds);setCode("");setFieldError(undefined);
      setNotice("Um novo código foi enviado. Use o código do e-mail mais recente.");
    } catch(cause) {
      setError(cause instanceof ApiError?cause.message:"Não foi possível reenviar. Tente novamente.");
      if(cause instanceof ApiError&&cause.code==="verification_cooldown")setWait(60);
    } finally {lock.current=false;setPending(null);}
  }
  return <AuthLayout>
    <span className="auth-form-icon"><MailCheck size={23} aria-hidden="true"/></span>
    <p className="eyebrow">CONFIRMAÇÃO DE E-MAIL</p><h1>Confirme seu e-mail.</h1>
    {verification?<>
      <p className="auth-intro">Enviamos um código de 6 dígitos para <strong>{verification.email}</strong>. Ele vale por 10 minutos. Confira também a pasta de spam.</p>
      {error&&<div className="auth-feedback auth-feedback--error" role="alert"><p>{error}</p></div>}
      {notice&&<div className="auth-feedback auth-feedback--success" role="status"><p>{notice}</p></div>}
      <form noValidate onSubmit={event=>void submit(event)} aria-busy={pending!==null}>
        <fieldset disabled={pending!==null}>
          <FormField id="verification-code" name="code" label="Código de confirmação" ref={input} autoFocus inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={code} error={fieldError} hint="Use o código mais recente. Você tem até 5 tentativas por código." onChange={event=>setCode(event.target.value.replace(/[^0-9]/g,""))}/>
          <button className="primary-button auth-submit" disabled={pending!==null}>{pending==="verify"?<><LoaderCircle className="animate-spin" size={17}/> Confirmando…</>:"Confirmar e-mail"}</button>
        </fieldset>
      </form>
      <p className="auth-switch"><button className="secondary-button" type="button" disabled={pending!==null||wait>0} onClick={()=>void resend()}>{pending==="resend"?"Reenviando…":wait>0?"Reenviar em "+wait+"s":"Reenviar código"}</button></p>
    </>:<p className="auth-intro">Entre com seu e-mail e senha ou inicie o cadastro para receber um código de confirmação.</p>}
    <p className="auth-switch"><Link to="/entrar" state={{from:returnPath}}>Voltar para o login</Link></p>
  </AuthLayout>;
}
