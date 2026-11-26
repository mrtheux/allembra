# Allembra · Android 0.3.0

Allembra é uma cápsula do tempo pessoal. De mim, para mim, daqui a um tempo. A interface usa uma ampulheta dourada, fundo creme, lilás para ações secundárias e serifa nos títulos. A navegação passa por Registrar, Minhas cápsulas, Lembretes e Conta e backup.

## Instalar e compilar

- O APK de teste roda a partir do Android 9. No Android Studio, abra esta pasta, selecione JDK 17, instale Android SDK 35 e execute o módulo `app` no emulador ou em um dispositivo.
- No Linux, use `./build-apk.sh` com JDK 17, `zip`, SDK 35 e Build Tools 35.0.0. Defina `ANDROID_SDK_ROOT` ou `ANDROID_HOME` para apontar ao SDK. O APK assinado para teste sai em `build/preview/Allembra-teste.apk`.
- O identificador provisório é `br.appdaqui.capsula`. A compilação de teste usa chave própria, guardada no ambiente de build. Distribuições públicas devem usar chave de produção e Play App Signing.

## Usar

1. Em **Lembretes**, defina um intervalo de 1 a 8.760 horas. A partir do Android 13, o sistema pede autorização. O agendamento é aproximado e reinicia a contagem após o aparelho reiniciar.
2. Em **Registrar**, escolha texto, pensamento, foto, vídeo de até 30 segundos ou áudio de até 60 segundos. Cada mídia pode ter até 12 MiB. Texto e pensamento aceitam até 4.000 caracteres.
3. Para foto e vídeo, o Android abre o app de câmera através de um intent e concede acesso temporário ao arquivo de captura. O app de câmera gerencia suas permissões. Para gravação de áudio, o Allembra solicita o microfone no momento do uso. O player padrão lê arquivos do cache privado, sem pedir acesso à galeria nem ao armazenamento geral.
4. Selecione uma cápsula individual ou crie um compilado com nome. Registros adicionados a um compilado herdam a abertura marcada no primeiro registro. A data surpresa é sorteada por dia entre 3 meses e 3 anos, inclusive, e fica oculta na interface enquanto a cápsula estiver selada.
5. As datas são calculadas e exibidas no fuso de Brasília (`America/Sao_Paulo`), independentemente de onde o aparelho esteja. A versão de teste usa o relógio do sistema como fonte do instante atual. A consulta ao site [Horário de Brasília](https://www.horariodebrasilia.org/) retornou HTTP 403 no ambiente de desenvolvimento, então nenhuma integração de hora online foi confirmada. Antecipar a abertura mudando o relógio ainda é possível.
6. Em **Minhas cápsulas**, toque nas lembranças disponíveis. Se o aparelho usa PIN, padrão ou senha, a entrada no app pede essa credencial.

## Arquivo local e próxima integração

A versão de teste funciona offline. O arquivo local usa AES-256-GCM e uma chave no Android Keystore. O backup padrão do Android fica desativado. A captura e a reprodução usam arquivos temporários no cache privado, limpos ao concluir os fluxos ou ao iniciar novamente. A câmera do aparelho é um app separado e pode ter comportamento próprio.

### Ativar o Appwrite Cloud

1. Crie um projeto Appwrite Cloud Free e adicione uma plataforma **Android** com o identificador `br.appdaqui.capsula`. Esse identificador interno foi mantido para preservar a instalação e o arquivo local de testes anteriores, quando o APK usa a mesma chave de assinatura.
2. Ative autenticação por e-mail e senha. Crie um bucket dedicado e privado, habilite **File Security** e permita **Create** apenas para usuários com e-mail verificado. Não dê permissão geral de leitura, alteração ou exclusão no bucket. O cliente cria objetos com leitura para o dono pelo ID da conta. Deixe a extensão `caps` permitida e ajuste o limite de arquivo para pelo menos 13 MiB. Os objetos já são cifrados no aparelho.
3. Registre seu domínio como plataforma **Web**. A rota HTTPS de confirmação recebe `userId` e `secret` do link e chama `account.updateVerification({ userId, secret })` no SDK Web do Appwrite. A rota de redefinição recebe os mesmos parâmetros, solicita a nova senha e chama `account.updateRecovery({ userId, secret, password })`. Evite analytics e recursos de terceiros nessas rotas, pois os parâmetros são credenciais temporárias. Use as URLs dessas páginas na configuração. Sem a página de confirmação, o app não libera o backup para novas contas.
4. Preencha `app/src/main/assets/appwrite.properties` para builds locais. Para builds do GitHub, preencha **Settings → Secrets and variables → Actions → Variables** com `APPWRITE_ENDPOINT`, `APPWRITE_PROJECT_ID`, `APPWRITE_BUCKET_ID`, `APPWRITE_VERIFICATION_URL` e, quando a rota estiver pronta, `APPWRITE_RECOVERY_URL`. Use o endpoint completo do projeto, por exemplo `https://fra.cloud.appwrite.io/v1`. Esses dados são identificadores públicos. O app nunca precisa de uma chave administrativa.
5. Compile novamente. Crie a conta, entre, confirme seu e-mail e gere o código de recuperação antes do primeiro backup. No segundo aparelho, entre com a mesma conta e restaure os registros com o mesmo código. O backup é manual. Redefinir a senha não recupera o código de recuperação. Cookies de sessão ficam em memória e são descartados ao fechar o processo.
6. Valide com duas contas e dois aparelhos. Teste que a segunda conta não lista nem lê backups da primeira, e que o bucket rejeita escrita por contas não verificadas. Teste confirmação por e-mail, recuperação de senha, restauração com código inválido, envio acima de 5 MiB, interrupção de rede e repetição do envio. Registros completos são identificados pelo ID da cápsula. Um envio interrompido em blocos pode deixar um arquivo incompleto, ignorado na restauração. Planeje limpeza periódica pelo administrador antes de usar o bucket em produção.

[Configuração Android do Appwrite](https://appwrite.io/docs/quick-starts/android) · [Permissões do Storage](https://appwrite.io/docs/products/storage/permissions) · [Planos](https://appwrite.io/pricing)

Se a abertura precisar resistir ao adiantamento do relógio ou a um cliente modificado, o serviço precisará validar o instante de Brasília e controlar a entrega da chave no prazo. Usar um relógio online apenas na interface limita a manipulação do relógio local, mas deixa a chave sob controle do aplicativo. Para uma distribuição pública, planeje exportação, exclusão de dados, retenção, continuidade e política de privacidade.

## Validação

O APK foi compilado com SDK 35. Valide câmera, microfone, reprodução e notificações em hardware real. A integração com Appwrite precisa de um projeto configurado e de testes de ponta a ponta.

No GitHub, abra **Actions → APK de teste Android**, escolha uma execução bem-sucedida e baixe **Allembra-teste** em **Artifacts**. Extraia o ZIP e abra o APK no Android. A chave do APK do Actions é gerada a cada execução. Faça backup dos registros antes de desinstalar uma compilação anterior.
