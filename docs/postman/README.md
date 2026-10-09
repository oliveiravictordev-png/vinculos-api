# Exemplos de uso da Vínculos API

## Postman

Importe estes dois arquivos pelo botão **Import**:

1. `Vinculos-API.postman_collection.json`
2. `Vinculos-Local.postman_environment.json`

O segundo arquivo existe apenas no ambiente local e é ignorado pelo Git porque contém as credenciais. Selecione o ambiente **Vínculos - Produção** no canto superior direito do Postman.

As requisições das pastas **Consultas** e **Erros esperados** executam o login automaticamente antes de cada chamada e salvam o JWT em `accessToken`. Também é possível executar manualmente um dos exemplos da pasta **Autenticação**.

## IntelliJ IDEA

Abra `docs/api-examples.http`. No seletor de ambiente do editor HTTP, escolha `production` ou `local` e execute primeiro um dos blocos de login. O script salva o JWT automaticamente; depois disso, qualquer consulta pode ser executada pelo ícone verde ao lado dela.

O IntelliJ IDEA já possui cliente HTTP integrado, portanto não precisa instalar extensão.

## VS Code

O mesmo arquivo `docs/api-examples.http` pode ser usado por extensões compatíveis com arquivos HTTP. Para garantir o armazenamento automático do token com o script incluído, prefira o cliente HTTP do IntelliJ ou importe a coleção no Postman.

## Arquivos locais protegidos

- `Vinculos-Local.postman_environment.json`: ambiente Postman preenchido.
- `../http-client.private.env.json`: variáveis privadas do cliente HTTP do IntelliJ.

Há exemplos de login com os dois administradores, consulta de empresas, registros em uma ou quatro empresas, documento com máscara, erro de validação, health check e OpenAPI.
