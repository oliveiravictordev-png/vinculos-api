/* global use, db */
// MongoDB Playground - consultas e metricas da base local.
// Altere somente os valores desta secao para pesquisar outro cliente.

use("vinculos");

const ano = 2026;
const tipoDocumento = "CPF";
const documento = "05666666690"; // somente numeros, com zeros a esquerda
const empresa = "10028131000130"; // CNPJ opcional para a consulta por empresa

const filtroCliente = {
  a: ano,
  t: tipoDocumento,
  v: documento
};

// 1. Total de documentos da colecao (rapido, usa metadados).
db.vinculos.estimatedDocumentCount();

// 2. Todos os registros do CPF informado.
// O limite protege a interface caso o filtro seja alterado por engano.
db.vinculos
  .find(filtroCliente)
  .sort({ e: 1 })
  .limit(100);

// 3. Quantidade exata de registros do CPF informado (usa o indice).
db.vinculos.countDocuments(filtroCliente);

// 4. Empresas distintas vinculadas ao CPF.
db.vinculos.distinct("e", filtroCliente);

// 5. Registros do CPF em uma empresa especifica.
db.vinculos
  .find({ ...filtroCliente, e: empresa })
  .limit(100);

// 6. Resumo do CPF: registros, empresas e soma dos valores em centavos/reais.
db.vinculos.aggregate([
  { $match: filtroCliente },
  {
    $group: {
      _id: null,
      registros: { $sum: 1 },
      empresas: { $addToSet: "$e" },
      valorTotalCentavos: { $sum: "$s" }
    }
  },
  {
    $project: {
      _id: 0,
      registros: 1,
      quantidadeEmpresas: { $size: "$empresas" },
      empresas: 1,
      valorTotalCentavos: 1,
      valorTotalReais: { $divide: ["$valorTotalCentavos", 100] }
    }
  }
]);

// 7. Amostra da colecao. Nunca remova o limit em uma base de 1 bilhao.
db.vinculos.find({}).limit(20);

// 8. Metricas de armazenamento em GB: dados, disco e indices.
db.runCommand({ collStats: "vinculos", scale: 1073741824 });

// 9. Bancos existentes e respectivos tamanhos em disco.
db.adminCommand({ listDatabases: 1 });

// 10. Indices existentes na colecao.
db.vinculos.getIndexes();

// 11. Plano da busca por CPF. Procure IXSCAN/DISTINCT_SCAN no resultado.
db.vinculos.find(filtroCliente).explain("executionStats");

// Evite countDocuments({}) e find({}) sem limit: eles podem percorrer ou
// tentar exibir uma parte enorme da colecao. Para o total, use sempre
// estimatedDocumentCount().
