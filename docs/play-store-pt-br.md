# Allembra · preparação para a Play Store

## Ficha em português do Brasil

- Nome: **Allembra**
- Categoria proposta: **Estilo de vida**
- Desenvolvedor a confirmar na Play Console: **Instituto Cogita**
- Descrição curta: **Guarde o agora. Reencontre depois. Uma cápsula do tempo pessoal.**

### Descrição completa

Guarde o agora. Reencontre depois.

O Allembra é uma cápsula do tempo pessoal. Guarde pensamentos, fotos, vídeos e áudios. Escolha quando quer relembrar e encontre, mais tarde, um pedaço de quem você era.

- Registre um momento no seu ritmo.
- Escolha quando abrir uma cápsula, de um mês a três anos, ou uma data surpresa.
- Acompanhe a semana e reveja registros ainda abertos.
- Proteja os registros no cofre do aparelho e faça uma cópia criptografada no local que você escolher.
- Ative lembretes e compartilhe uma lembrança quando quiser.

Um app do Instituto Cogita.

Antes de colar a descrição na loja, valide no aparelho os recursos anunciados. Capture as telas do APK final, sem exibir frases, e-mails nem registros pessoais.

## Inventário para a Segurança dos dados

| Dados | Caminho na implementação atual |
|---|---|
| Pensamentos, fotos, vídeos, áudio e datas | Processados no aparelho. O cofre no armazenamento privado usa AES-256-GCM. A captura usa recursos do Android após a autorização. |
| Frase de recuperação | Deriva a chave localmente. A opção de biometria guarda uma cópia cifrada com chave do Android Keystore protegida por autenticação. O e-mail de emergência, quando solicitado, contém um arquivo cifrado com uma senha independente. |
| Biometria | O Android mostra o prompt e entrega um resultado de autenticação. O Allembra não recebe imagens nem moldes de impressão digital. |
| E-mail de recuperação | Digitado para montar um compartilhamento escolhido pelo usuário. Sem cadastro, serviço próprio de envio ou envio automático. |
| Backup | O usuário escolhe um arquivo local ou um provedor como o Google Drive via seletor do Android. O arquivo contém o envelope cifrado. A frequência automática depende de acesso persistente ao destino. |
| Compartilhamento | Texto, print ou arquivo cifrado sai do aparelho somente por ação do usuário, pelo app de destino que ele escolher. |
| Rede e SDKs | O APK desta prévia não declara permissão de internet. Usa fontes e biblioteca de ícones incorporadas, sem analytics ou anúncios. Reavalie o inventário se adicionar conta, Appwrite, telemetria ou um serviço de hora remota. |

O formulário final deve corresponder ao binário assinado e aos provedores usados. Revise as definições de coleta, compartilhamento por ação iniciada pelo usuário e provedores externos antes de marcar respostas. [Segurança dos dados no Google Play](https://support.google.com/googleplay/android-developer/answer/10787469)

### Texto-base para a política de privacidade

O Allembra é um aplicativo do Instituto Cogita. Ele processa pensamentos, fotos, áudios, vídeos e datas de registro no dispositivo. As permissões de câmera, microfone e lembretes são solicitadas quando necessárias às funções que você usa. Os registros são armazenados em um cofre criptografado, protegido pela sua frase de recuperação. Se você ativar o desbloqueio biométrico, a verificação é feita pelo Android.

Você pode guardar cópias cifradas em um local de sua escolha. Se escolher Google Drive ou outro provedor, aplica-se também a política desse serviço. Para usar um backup, você precisa do arquivo e da frase que o protege. A opção de recuperação por e-mail prepara um arquivo de chave cifrado com uma senha de emergência, e você controla o destinatário e o envio no aplicativo escolhido. Ao compartilhar uma lembrança, o serviço destinatário tratará o conteúdo conforme suas próprias regras.

Os registros locais permanecem no aparelho até a exclusão dos dados do app ou sua desinstalação. Cópias externas permanecem no destino escolhido até serem excluídas por você. Para dúvidas sobre privacidade, escreva para **[e-mail oficial do Instituto Cogita]**.

O texto acima é um rascunho. Confirme contato, retenção, práticas do binário final e URL pública. A política publicada deve constar na ficha da loja e dentro do aplicativo. [Política de dados do usuário](https://support.google.com/googleplay/android-developer/answer/10144311)

## Do AAB ao teste

1. Confirme o proprietário e o tipo da conta Play Console, a identidade de desenvolvedor, o ID de pacote definitivo e o e-mail de suporte e privacidade.
2. Compile com alvo API 36, um código de versão monotônico e a chave de upload definitiva. Habilite o Play App Signing. O AAB de rascunho deste repositório usa identificação de teste e não está assinado com a chave de produção. [API alvo](https://developer.android.com/google/play/requirements/target-sdk) · [Preparar uma versão](https://support.google.com/googleplay/android-developer/answer/9859348)
3. Prepare ícone de loja, imagem promocional, capturas do dispositivo, classificação indicativa, declaração de anúncios e público-alvo. Preencha **Acesso ao app** com o roteiro para criar uma frase de teste e explique que o fluxo principal funciona sem conta externa. Não entregue credenciais reais.
4. Comece na faixa interna. Se a conta for pessoal e tiver sido criada após 13 de novembro de 2023, cumpra o teste fechado aplicável antes de solicitar produção. A orientação atual exige ao menos 12 participantes inscritos continuamente durante 14 dias. [Teste para contas pessoais](https://support.google.com/googleplay/android-developer/answer/14151465)
5. Valide no S25 e em outro fabricante ou versão do Android: foto com foco, vídeo com som, recusa de permissões, microfone, rotação, retorno da câmera, biometria, proporções do print, perda do aparelho, backup restaurado e múltiplos anos de datas. Verifique também contraste, fonte ampliada e navegação por leitor de tela.
6. Decida a política de liberação. Esta versão usa o relógio do aparelho para abrir cápsulas. Uma promessa de retenção resistente a alteração do relógio exige serviço de tempo e controle da chave fora do dispositivo.

O PDF *Google Security Overview* de outubro de 2026 é referência para defesa em camadas, criptografia, acesso e resposta a incidentes. Ele descreve os controles do Google Cloud. A publicação do Allembra exige as declarações e verificações acima, com base no comportamento próprio do app.
