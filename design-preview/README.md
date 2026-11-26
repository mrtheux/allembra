# Prévia de design do Allembra

Compile com `./build-apk.sh --design-preview`, a partir da raiz do repositório. Use JDK 17 e Android SDK 35. O APK sai em `build/design-preview/Allembra-teste.apk`.

O pacote `br.appdaqui.designpreview` permite instalar a prévia ao lado do Allembra anterior. O design offline usa fontes, ícones e sons incorporados. O Android pede acesso à câmera e ao microfone no ponto de uso. Registros e preferências desta prévia são temporários. A notificação é demonstrativa.

O APK de teste abre com o ícone Vermelho laca. Os seis desenhos ficam em `src/main/res/drawable`. Para a versão Android final, oferecer as seis opções em **Ajustes → Ícone do app**, com miniatura, indicação da opção ativa e persistência. O padrão da versão final será definido após a escolha. No cabeçalho, a marca fica diretamente sobre o fundo.
