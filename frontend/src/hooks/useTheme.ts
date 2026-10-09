import { useEffect, useState } from "react";

type Theme = "light" | "dark";
const storageKey = "roboparts-theme";

function initialTheme(): Theme {
  try {
    const savedTheme = localStorage.getItem(storageKey);
    if (savedTheme === "light" || savedTheme === "dark") return savedTheme;
  } catch {
    // O tema continua funcional quando o navegador bloqueia armazenamento local.
  }
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(initialTheme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(storageKey, theme);
    } catch {
      // Persistência opcional: não impedir a troca de tema.
    }
  }, [theme]);

  return {
    theme,
    toggleTheme: () =>
      setTheme((current) => (current === "dark" ? "light" : "dark")),
  };
}
