export interface User {
  id: string;
  name: string;
  email: string;
}

export interface RegistrationInput {
  name: string;
  email: string;
  password: string;
  accessCode: string;
}

export interface UserActivity {
  id: string;
  action: string;
  createdAt: string;
}
