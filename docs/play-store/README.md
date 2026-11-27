# Allembra na Google Play

A ficha em português, o rascunho da política de privacidade e as orientações de Segurança dos dados estão em [Preparação para a Google Play](../play-store-pt-br.md).

## Materiais da ficha

- [Ícone Vermelho laca, 512 × 512 px](allembra-icone-512.png)
- [Imagem promocional, 1024 × 500 px](allembra-imagem-promocional-1024x500.png)
- Capturas de celular a produzir com dados fictícios. A sequência sugerida é Registrar, Sua semana, Quando relembrar? e Relicário. A versão normal bloqueia capturas de tela no Android. Use uma compilação separada para as capturas da loja, com conteúdo de demonstração e permissão de captura restrita a essa compilação.

## Roteiro de teste interno

| Fluxo | O que verificar no celular |
| --- | --- |
| Primeiro acesso | Criar a chave, alternar sua visibilidade, aceitar ou pular a biometria e reabrir o app. |
| Câmera e microfone | Negar e depois conceder as permissões. Tirar uma foto, tocar para focar e gravar vídeo com contagem regressiva. Gravar, pausar, retomar e ouvir áudio. Conferir se controles permanecem na tela após voltar. |
| Cápsula | Registrar um pensamento, abrir a prévia do dia na semana e selar. Conferir que o conteúdo selado fica inacessível antes da abertura e que o prazo usa o horário de Brasília. |
| Notificações | Verificar com permissão concedida e negada. Abrir o aviso e tocar fora para fechá-lo. |
| Backup | Usar uma cápsula fictícia. Gerar um backup local e outro no Google Drive. Restaurar em outra instalação com a chave correta e verificar a rejeição de uma chave incorreta. Conferir as opções diária e semanal. |
| Relicário | Com uma cápsula de teste já liberada, alternar entre galeria e cartões. Compartilhar em 1:2 e 9:16. Conferir as dimensões da imagem e verificar que o seletor de proporção ficou fora da captura. |

Faça a primeira rodada no Galaxy S25 e repita câmera, microfone e restauração em outro aparelho Android. Registre o modelo, a versão do Android e o resultado de cada fluxo.

## Pendências antes de enviar para revisão

- Confirmar o e-mail público de suporte e publicar a política de privacidade com URL acessível. Conferir se o link também aparece no app.
- Conferir as declarações de Segurança dos dados e Acesso ao app contra a versão que será enviada. Usar contas e conteúdo de teste onde aplicável.
- Concluir as capturas de celular e o teste interno. Após a verificação da conta, conferir no Play Console quais etapas de teste se aplicam para solicitar acesso à produção.
