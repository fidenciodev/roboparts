import { createRequire } from "node:module";

export default function prepareTestEnvironment() {
  // A primeira leitura do jsdom pode ser lenta no Windows/OneDrive. Carregue-o
  // antes de iniciar o prazo de comunicação dos workers, mantendo o isolamento.
  // jsdom é CommonJS; carregá-lo diretamente evita a ponte de importação do runner.
  createRequire(import.meta.url)("jsdom");
}
