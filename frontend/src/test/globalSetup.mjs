export default async function prepareTestEnvironment() {
  // A primeira leitura do jsdom pode ser lenta no Windows/OneDrive. Carregue-o
  // antes de iniciar o prazo de comunicação dos workers, mantendo o isolamento.
  await import("jsdom");
}
