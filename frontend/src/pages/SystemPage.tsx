import { Cable, Info } from "lucide-react";
import { SystemHealth } from "../components/SystemHealth";

export function SystemPage() {
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">DIAGNÓSTICO DO AMBIENTE</p>
          <h1>Uma base conectada.</h1>
          <p>Confira a comunicação entre o frontend, a API e o PostgreSQL.</p>
        </div>
        <span className="stage-tag">
          <span />
          Ambiente local
        </span>
      </div>
      <SystemHealth detailed />
      <section
        className="panel connection-help"
        aria-labelledby="connection-help-title"
      >
        <span className="section-icon">
          <Cable size={23} aria-hidden="true" />
        </span>
        <div>
          <h2 id="connection-help-title">Como funciona a verificação</h2>
          <p>
            O frontend consulta a API. O backend verifica a conexão com o
            PostgreSQL e informa a versão da migração Flyway aplicada.
          </p>
          <div className="information-note">
            <Info size={18} aria-hidden="true" />
            <p>
              Se a consulta falhar, inicie o backend e confira as variáveis de
              ambiente conforme o README do projeto. Depois, selecione{" "}
              <strong>Verificar agora</strong>.
            </p>
          </div>
        </div>
      </section>
    </>
  );
}
