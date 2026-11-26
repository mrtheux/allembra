# Allembra · teste Android

O pacote `br.appdaqui.designreview` instala ao lado das prévias anteriores. O ícone desta versão é Vermelho laca. As seis variações estão em `src/main/res/drawable`. O seletor de ícones do launcher está previsto para a versão final.

## Testar no celular

1. No primeiro acesso, crie uma frase de recuperação longa. Se escolher oferecer a frase a um gerenciador, o app chama o serviço de preenchimento do Android. Teste a oferta com Samsung Pass ou Google Password Manager configurado. O serviço do aparelho decide se apresenta a opção.
2. Autorize câmera ao abrir foto ou vídeo, microfone ao gravar áudio ou vídeo com som, e notificações ao ativar lembretes. Teste também a recusa, ida aos Ajustes do Android e retorno. As cópias usam o seletor de documentos, sem autorização geral de armazenamento.
3. Compare foco, iluminação, orientação e qualidade de uma foto e um vídeo com o Galaxy S25. O app solicita câmera traseira e resolução ideal de 1080p, tenta foco contínuo quando exposto pelo aparelho e usa a captura fotográfica do WebView quando disponível. O vídeo para aos 30 segundos. Hardware, WebView e permissões determinam o resultado.
4. Toque na pena após capturar e revise a mídia. **Gravar** mantém o registro revisável no dia. **Selar** pré-sela aquele conteúdo. **Descartar** fecha sem guardar. Em **Cápsulas**, toque no dia para expandir, deslize para outra semana ou mantenha o dia pressionado para o calendário. Defina os prazos em **Quando relembrar?**.
5. Em **Ajustes → Backup**, escolha um arquivo local ou Google Drive, se o Drive aparecer no seletor. Faça uma cópia e teste a recuperação com a frase certa e uma frase errada, inclusive após reinstalar em um aparelho de teste. Recuperar exige confirmação e substitui o cofre local. Marque diária ou semanal e verifique se o provedor permite atualizar o arquivo em segundo plano. O Android pode adiar os trabalhos. Preserve uma segunda cópia até validar restauração.

## Segurança e limites desta versão

- Cofre local com AES-256-GCM, IV aleatório por escrita e chave derivada da frase por PBKDF2-HMAC-SHA-256, 600 mil iterações e salt aleatório. A frase é usada durante o desbloqueio, sem escrita em texto claro. O backup contém apenas o envelope cifrado. O app pede a frase e o arquivo para recuperar em outro aparelho.
- A cópia automática do Android está desativada. A cópia escolhida pelo usuário é gravada no provedor de documentos. Esta versão trabalha offline e usa os controles do sistema para abrir ou exportar um arquivo. Sem Drive disponível no seletor ou sem permissão persistente, o envio automático ao Drive fica indisponível. A cópia local do cofre continua existindo.
- O WebView só carrega recursos incorporados e a ponte Android tem operações limitadas a cofre, autenticação por preenchimento do sistema, documentos e preferências. Capturas e miniaturas de tela são bloqueadas. A frase segue sob controle do usuário e do gerenciador configurado.
- A trava de prazo usa o fuso de Brasília e o relógio do aparelho. Uma trava resistente a mudança de relógio ou a um cliente modificado depende de liberação de chaves externa. O fundo e as ondas precisam de teste de desempenho em hardware real. O cofre de teste suporta até 32 MB de envelope cifrado e até 15 MB por mídia.
- A restauração automatizada em JavaScript validou autenticação do envelope, frase errada e conteúdo pré-selado invisível. Teste no aparelho com Drive, Samsung Pass, câmera, permissões e agendamento. Faça revisão de segurança independente antes de distribuir a público.

## Preparação da Play Store

- APK: `./build-apk.sh --design-preview` com Android SDK 36 e JDK 17.
- AAB de rascunho: defina `BUNDLETOOL_JAR` e execute `./build-apk.sh --design-preview --bundle`. O script valida `build/design-preview/Allembra-rascunho.aab`.
- Antes de subir à loja, defina o ID definitivo, mantenha código de versão monotônico, use uma chave de upload própria e configure Play App Signing na conta do Instituto Cogita. O AAB de rascunho está sem assinatura de produção.
- Complete a página da loja, capturas, política de privacidade, formulário de segurança de dados e faixa de teste. Valide tela inicial, 120 Hz, acessibilidade, estado offline, permissões recusadas, processos encerrados, backup restaurado e mudança de relógio.
- Referência de API alvo: [Google Play](https://developer.android.com/google/play/requirements/target-sdk).

## Testes da revisão 0.6

A revisão tem ID de instalação separado para preservar o Allembra Teste 0.5. Para migrar um cofre de teste, exporte no 0.5 e recupere o arquivo no 0.6 com a mesma frase. Em **Ajustes → Backup**, **Fazer backup no Google Drive** abre o seletor do Android. Se Drive não aparecer, salve em arquivo local e use **Enviar uma cópia criptografada** ou envie pelo app do Drive.

Foto e vídeo são capturados pelo app de câmera do Android. O Allembra solicita limite de 30 segundos e valida a mídia recebida. A interface durante a captura, inclusive o contador, é controlada pelo app de câmera. O áudio separado usa o gravador nativo. Os limites provisórios ainda se aplicam.

A configuração de biometria exige a frase e autenticação forte do dispositivo. A frase de emergência por e-mail fica cifrada com uma senha independente. O app abre o painel de compartilhamento para revisão, com o destino preenchido quando o cliente aceita essa informação.

A ficha, o inventário de dados e o rascunho de política estão em [Preparação para a Play Store](../docs/play-store-pt-br.md).
