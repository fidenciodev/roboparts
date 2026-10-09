import type { RegistrationInput } from "../types/auth";

export function validateLogin(email: string, password: string): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()) || email.trim().length > 254) errors.email = "Informe um e-mail válido.";
  if (password.length < 12 || password.length > 128) errors.password = "Informe sua senha de 12 a 128 caracteres.";
  return errors;
}

export function validateRegistration(input: RegistrationInput, confirmation: string): Record<string, string> {
  const errors = validateLogin(input.email, input.password);
  if (input.name.trim().length < 2 || input.name.trim().length > 100) errors.name = "Informe seu nome, com 2 a 100 caracteres.";
  if (input.password !== confirmation) errors.confirmation = "A confirmação precisa ser igual à senha.";
  if (!input.accessCode.trim() || input.accessCode.length > 256) errors.accessCode = "Informe o código de acesso fornecido pela sua equipe, com até 256 caracteres.";
  return errors;
}

export function focusFirstInvalid(form: HTMLFormElement | null, errors: Record<string, string>) {
  const field = Array.from(form?.querySelectorAll<HTMLInputElement>("input[name]") ?? [])
    .find((input) => Boolean(errors[input.name]));
  field?.focus();
}
