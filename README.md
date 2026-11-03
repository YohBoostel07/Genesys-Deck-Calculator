# Genesys Deck Calculator

Aplicativo Android independente para pesquisar cartas de Yu-Gi-Oh! e montar decks no formato Genesys.

## Estado atual

Esta é a primeira base funcional do projeto:
- Interface Android nativa com Jetpack Compose.
- Catálogo geral de cartas retornado pela YGOPRODeck API v7, com nomes em português quando houver tradução e fallback para o nome original em inglês.
- Pontuações Genesys associadas por ID de carta a partir da consulta específica do formato Genesys.
- Catálogo combinado e armazenado localmente para pesquisas posteriores sem baixar tudo novamente.
- Exibição de imagem, nome, tipo e pontuação Genesys.
- Adição e remoção de cartas no deck.
- Limite de até 3 cópias por carta na interface inicial.
- Soma da pontuação e aviso visual acima de 100 pontos.
- Organização em Main Deck, Extra Deck e Side Deck.
- Importação de deck por código YDKE (`ydke://`) ou pelo conteúdo de arquivo `.ydk` do EDOPro.
- Cópia da lista do deck para a área de transferência.
- GitHub Actions configurado para compilar um APK de depuração.

## Compilação

O workflow **Android APK** executa automaticamente a cada push na branch `main` e também pode ser iniciado manualmente em Actions. O APK é publicado como artefato chamado `Genesys-Deck-Calculator-APK`.

## Fonte de dados

As informações das cartas e o campo `genesys_points` vêm da API v7 do YGOPRODeck, consultada com `format=genesys`. A API também fornece imagens de cartas. Consulte https://ygoprodeck.com/api-guide/.

## Próximas etapas

- Validar o APK em execução num dispositivo Android.
- Completar separação de Main Deck, Extra Deck e Side Deck.
- Implementar salvamento local de vários decks, edição, duplicação e compartilhamento.
- Adicionar validação completa de legalidade e limites do formato a partir da lista oficial vigente.
- Melhorar filtros, detalhes de cartas e experiência visual.

**Importante:** esta versão inicial ainda não deve ser tratada como validador oficial completo de torneio.
