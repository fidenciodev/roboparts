import { ArrowLeft, MapPin } from "lucide-react";
import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <section className="panel not-found">
      <span className="section-icon">
        <MapPin size={25} aria-hidden="true" />
      </span>
      <p className="eyebrow">PÁGINA NÃO ENCONTRADA · 404</p>
      <h1>Este caminho não existe.</h1>
      <p>Volte à visão geral para continuar no RoboParts.</p>
      <Link className="secondary-button" to="/">
        <ArrowLeft size={16} aria-hidden="true" />
        Voltar ao início
      </Link>
    </section>
  );
}
