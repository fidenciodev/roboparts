import { CircleCheck, CircleDashed, CircleX } from "lucide-react";
import type { ReactNode } from "react";

export type StatusTone = "success" | "error" | "neutral";

interface StatusBadgeProps {
  tone: StatusTone;
  children: ReactNode;
}

export function StatusBadge({ tone, children }: StatusBadgeProps) {
  const Icon =
    tone === "success"
      ? CircleCheck
      : tone === "error"
        ? CircleX
        : CircleDashed;
  return (
    <span className={`status-badge status-badge--${tone}`}>
      <Icon aria-hidden="true" size={14} />
      {children}
    </span>
  );
}
