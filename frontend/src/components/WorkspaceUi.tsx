import { useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { Check, ChevronLeft, ChevronRight, LoaderCircle, Pencil, RefreshCw, X } from "lucide-react";
export const formatDate = (value: string) => new Intl.DateTimeFormat("pt-BR",{dateStyle:"short",timeStyle:"short"}).format(new Date(value));
export const statusLabels: Record<string,string> = { PENDING:"Pendente", IN_PROGRESS:"Em andamento", COMPLETED:"Em uso", RETURNING:"Em devolução", RETURNED:"Devolvido", CANCELLED:"Cancelado" };
export const actionLabels: Record<string,string> = { USER_REGISTERED:"Cadastro de conta",USER_LOGGED_IN:"Entrada no sistema",USER_LOGGED_OUT:"Saída do sistema",
 ROBOT_CREATED:"Robô cadastrado",ROBOT_UPDATED:"Robô alterado",NODE_CREATED:"Elemento adicionado",NODE_UPDATED:"Elemento alterado",
 CHECKLIST_STARTED:"Retirada iniciada",COMPONENT_WITHDRAWN:"Componente retirado",COMPONENT_RETURNED:"Componente devolvido",CHECKLIST_FINALIZED:"Retirada conferida",CHECKLIST_CANCELLED:"Checklist cancelado" };
export function useAction() {
 const lock=useRef(false);const [busy,setBusy]=useState(false);const [error,setError]=useState<string|null>(null);const [success,setSuccess]=useState<string|null>(null);
 async function run(action:()=>Promise<unknown>,message="Alteração salva.") {
  if(lock.current)return false;lock.current=true;setBusy(true);setError(null);setSuccess(null);
  try {await action();setSuccess(message);return true;}catch(e){setError(e instanceof Error?e.message:"Não foi possível concluir a operação.");return false;}
  finally {lock.current=false;setBusy(false);}
 }
 return {busy,error,success,run,clear:()=>{setError(null);setSuccess(null);}};
}
export function Feedback({error,success}:{error:string|null;success:string|null}) {
 return <>{error&&<p className="workspace-feedback workspace-feedback--error" role="alert">{error}</p>}{success&&<p className="workspace-feedback" role="status">{success}</p>}</>;
}
export function QueryState({pending,error,retry}:{pending:boolean;error:Error|null;retry:()=>void}) {
 if(pending)return <p className="empty-state" role="status"><LoaderCircle className="animate-spin" size={20}/> Carregando dados…</p>;
 if(error)return <div className="empty-state"><p role="alert">{error.message}</p><button className="secondary-button" onClick={retry}><RefreshCw size={16}/> Tentar novamente</button></div>;
 return null;
}
export function Heading({title,description,children}:{title:string;description:string;children?:ReactNode}) {
 return <div className="page-heading workspace-heading"><div><p className="eyebrow">CONTROLE DE RETIRADAS</p><h1>{title}</h1><p>{description}</p></div><div className="workspace-actions">{children}</div></div>;
}
export function Pagination({page,setPage,hasNext}:{page:number;setPage:(n:number)=>void;hasNext:boolean}) {
 return <nav className="pagination" aria-label="Paginação"><button className="secondary-button" disabled={page===0} onClick={()=>setPage(page-1)}><ChevronLeft size={16}/> Anterior</button><span>Página {page+1}</span><button className="secondary-button" disabled={!hasNext} onClick={()=>setPage(page+1)}>Próxima <ChevronRight size={16}/></button></nav>;
}
export function InlineName({name,onSave,disabled=false}:{name:string;onSave:(value:string)=>Promise<boolean>;disabled?:boolean}) {
 const [editing,setEditing]=useState(false);const [value,setValue]=useState(name);const trigger=useRef<HTMLButtonElement>(null);
 function close(){setEditing(false);requestAnimationFrame(()=>trigger.current?.focus());}
 async function save(event:FormEvent){event.preventDefault();if(await onSave(value.trim()))close();}
 if(!editing)return <button ref={trigger} className="inline-name" disabled={disabled} onClick={()=>{setValue(name);setEditing(true);}} title="Editar nome"><span>{name}</span><Pencil size={14} aria-hidden="true"/></button>;
 return <form className="inline-editor" onSubmit={event=>void save(event)} onKeyDown={event=>{if(event.key==="Escape"){event.preventDefault();close();}}}>
  <input aria-label={"Novo nome de "+name} autoFocus required minLength={2} maxLength={100} value={value} onChange={event=>setValue(event.target.value)} disabled={disabled}/>
  <button className="icon-button" disabled={disabled} aria-label="Salvar nome"><Check size={18}/></button>
  <button className="icon-button" disabled={disabled} type="button" onClick={close} aria-label="Cancelar edição"><X size={18}/></button>
 </form>;
}
export function Progress({completed,total,label="Progresso"}:{completed:number;total:number;label?:string}) {
 return <div className="check-progress"><div><span>{label}</span><strong>{completed}/{total}</strong></div><progress max={Math.max(total,1)} value={completed} aria-label={label}/></div>;
}
