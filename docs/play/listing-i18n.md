# 有码 · Google Play 商品详情的其他语言

简体中文的文字、图片和写法约束见 [listing.md](listing.md)。这里是其余语言：手写的七种（英、日、韩、法、拉美西语和两种繁中）全文，
一次性商品「去除水印」各语言的名称和说明，以及哪些语言交给 Play 的机器翻译。

## 语言一览

| 语言 | 文字 | 截图、置顶大图 |
|---|---|---|
| en-US（默认） | 手写，下文 | 英文版：[`screenshots-en/`](screenshots-en/)、[`feature-graphic-en.png`](feature-graphic-en.png) |
| zh-CN | 手写，见 [listing.md](listing.md) | 中文版：[`screenshots/`](screenshots/)、[`feature-graphic.png`](feature-graphic.png) |
| ja-JP、ko-KR、fr-FR、es-419 | 手写，下文 | 不单独上传，回落到默认语言（英文）的 |
| zh-TW、zh-HK | 由简中转写，下文 | 同上 |
| ar、cs-CZ、da-DK、de-DE、es-ES、fi-FI、hi-IN、hu-HU、id、it-IT、iw-IL、nl-NL、no-NO、pl-PL、pt-BR、ro、ru-RU、sk、sv-SE、th、tr-TR、uk、vi | Play 免费机器翻译，源文是下文「机器翻译」一节的压缩版英文，标题换成手写的 | 同上 |
| 其余语言 | 不加译文：显示默认语言（英文），Play 商店页上用户可以点开 Google 的自动翻译 | 同上 |

改了英文正文之后，机翻的 23 种不会跟着变，要照「机器翻译」一节重新下单、换标题、应用；手写的几种要各自改。

## 写法上的约束

- **界面只有英文和简体中文**：繁中、日、韩、法、西语用户打开看到的是英文界面（values-zh 只给简体，见 `ui/AppLanguage.kt`），
  这几种语言的说明末尾都写明界面语言；提到按钮时用英文界面上的原词（Export、Mask / Off、Big Bang、AI review、Labs）。
- **不写做不到的**：取件码的规则只认简体中文字段名（`PICKUP_LABELS`），除简中以外都不提取件码；
  人名、地址的规则以中英文为主，非中英文的说明里各加一句；韩文（谚文）和泰文识别模型读不了，韩语说明专门写明。
- **社保号是美国的 SSN**：法、西、日语里写成「美国的社会保障号（SSN）」，免得被当成本国的号码。
- 其余照 [listing.md](listing.md)「写法上的约束」：首屏写明权限、付费只去水印、马赛克和模糊不写成安全手段、不提打赏。

## en-US · English (United States)

默认语言，也是机器翻译的源文。

### 应用名称（27/30）

```text
Yomark - Redact Screenshots
```

### 简短说明（79/80）

```text
Auto-redact phone numbers, names, addresses, faces and QR codes. Fully offline.
```

### 完整说明（3852/4000）

```text
Yomark finds the phone numbers, addresses, names, emails, faces and QR codes in your screenshots and masks them for you. Recognition, editing and export all happen on your phone: the app has no internet permission and never asks for camera, storage, location, contacts or any other permission.

PRIVACY
• No internet permission: not a promise not to upload, but an app the system never lets online. It works the same in airplane mode.
• No runtime permissions: the system photo picker hands the app only the images you choose, never the rest of your gallery.
• Its few other declarations read no data, such as BILLING from the Google Play Billing library, used only for the in-app purchase.
• Clean export: images are redrawn at full resolution, with EXIF data such as location, device model and capture time dropped.
• No account, no ads, no data collected.

HOW IT WORKS
1. Pick an image, or share several to Yomark from your gallery.
2. In a second or two, everything found is covered with sky-blue blocks. Tap a mask to remove it, tap unmasked text to mask it, and drag a box with one finger over anything it missed.
3. Tap Export to save to your gallery, or Share to send it right away without saving.

WHAT IT FINDS
Masked by default: phone numbers, emails, names, addresses, license plates, card numbers (with security code and expiry date), IBANs, ID and passport numbers, MRZs, SSNs, dates of birth, VINs, API keys, MAC addresses, faces, and QR codes and barcodes that decode.
Off by default: long numbers such as tracking and order numbers, dates and times, URLs, IP addresses.
Each kind can be set to Mask or Off in Settings.

Text is read by the bundled PP-OCRv6 model, and rules with check digits (such as the Luhn check on card numbers) decide what's sensitive, so every hit has a reason. Faces and barcodes are found by Google ML Kit's offline models, also bundled.
Names and addresses are recognized in English and Chinese text. Recognition isn't perfect, so give the image a look before you share it.

FIVE STYLES
Solid, Mosaic, Blur, Marker and Pen, and each mask can have its own style. Adjust colors, mosaic size, blur strength and line width, or pick a color from the image with the eyedropper. Preview and export use the same renderer, so what you see is what you export.
Note: screenshots have fixed fonts and clean backgrounds, so mosaic and blur can sometimes be reversed. To truly hide information, use Solid.

MORE
• Manual boxes: drag the corners to resize or the middle to move; pinch to zoom and pan.
• Big Bang: long-press text to lay out its characters one by one, handy for masking just part of a number.
• Text: write a line of text on the image.
• Batch: share several images or pick several in the app, check each one, then export them together.
• Purpose watermark: tile a line such as "For visa application only" across the whole image, so any other use stands out at a glance. Color, angle, opacity and density are adjustable.
• AI review: on supported devices (such as Pixel 9 and later), Gemini Nano on your phone reads the page again and masks what the rules missed, such as names mentioned in a sentence.
• Detect chat screenshots (Labs): masks avatars, nicknames and the chat title; may not be fully accurate.
• Dark theme follows the system, and the theme color follows your wallpaper (Android 12 and later) or one of seven presets.

PRICING
Free to download, with every feature and all privacy protection included. Free exports carry a small "Yomark" watermark in the bottom-right corner that keeps clear of your masks; a one-time purchase removes it for good. It's not a subscription, and it's the only paid item.

OPEN SOURCE
Source code, design docs and test records are public on GitHub: github.com/FlintyLemming/yomark

The interface is in English and Simplified Chinese. Requires Android 10 or later.
```

## ja-JP · 日本語

说明里写明人名、地址以中英文为主，平假名、片假名的名字认不出。

### 应用名称（24/30）

```text
Yomark - スクショの個人情報を自動で隠す
```

### 简短说明（49/80）

```text
スクショの電話番号・メール・カード番号・顔・QRコードを自動で隠す。端末内で処理し、通信しません。
```

### 完整说明（2197/4000）

```text
スクリーンショットに写った電話番号、メールアドレス、カード番号、顔、QRコードなどを、Yomark が自動で見つけて隠します。認識・編集・書き出しはすべて端末内で完結。アプリにはインターネット権限がなく、カメラ・ストレージ・位置情報・連絡先などの権限も一切求めません。

【プライバシー】
• インターネット権限なし：「アップロードしない」という約束ではなく、システムがこのアプリに通信を許していません。機内モードでもそのまま使えます。
• 実行時の権限なし：画像はシステムのフォトピッカーで選ぶので、アプリが受け取るのは選んだ画像だけ。ギャラリーのほかの写真は見えません。
• 宣言している権限は、データを読み取らないものがいくつかあるだけです。たとえば Google Play 請求サービスのライブラリが追加する BILLING は、アプリ内購入にのみ使います。
• クリーンな書き出し：元の解像度で描き直して再エンコードし、位置情報・機種・撮影日時などの EXIF 情報は残しません。
• アカウントなし、広告なし、データ収集なし。

【使い方】
1. 画像を選ぶか、ギャラリーから Yomark に共有します。複数枚まとめても OK。
2. 1〜2 秒で認識が終わり、見つかった箇所が水色のブロックで隠されます。マスクをタップすると外れ、隠れていない文字をタップすると隠せます。見落としは指 1 本でドラッグして枠を追加。
3. 「Export」でギャラリーに保存するか、共有ボタンで保存せずにそのまま送れます。

【検出する情報】
初期設定で隠す：電話番号、メールアドレス、名前、住所、ナンバープレート、カード番号（セキュリティコード・有効期限を含む）、IBAN、身分証の番号（在留カードなど）、パスポート番号と MRZ、米国の社会保障番号（SSN）、生年月日、車台番号（VIN）、API キー、MAC アドレス、顔、読み取れる QRコードとバーコード。
初期設定でオフ：追跡番号・注文番号などの長い数字列、日時、URL、IP アドレス。
種類ごとに、設定で「Mask」（隠す）か「Off」（オフ）を選べます。

文字はアプリに同梱の PP-OCRv6 モデルで読み取り、何が機密情報かはルールで判定します。正規表現とチェックディジット（カード番号の Luhn チェックなど）を使うので、どの検出にも理由があります。顔とバーコードは Google ML Kit のオフラインモデルで検出。モデルはすべてパッケージに含まれ、インストール直後からオフラインで動きます。
名前と住所の検出は中国語と英語のテキストが中心で、ひらがな・カタカナで書かれた名前はまだ認識できません。認識は完全ではないので、共有する前に一度確認してください。

【5 つのスタイル】
塗りつぶし（Solid）、モザイク、ぼかし、マーカー、ペン。マスクごとに別のスタイルを使えます。塗りの色（または周囲に近い色）、モザイクの粗さ、ぼかしの強さ、マーカーとペンの色・太さを調整でき、スポイトで画像から色も取れます。プレビューと書き出しは同じレンダラーなので、見たとおりに書き出されます。
注意：スクリーンショットは文字の形が決まっていて背景もきれいなため、モザイクやぼかしは復元されることがあります。確実に隠したいときは塗りつぶしを使ってください。

【その他の機能】
• 手動の枠：四隅をドラッグしてサイズ変更、中央をドラッグして移動。2 本指で拡大と移動。
• Big Bang：文字を長押しすると、認識した文字が 1 文字ずつ並びます。番号の一部だけ隠すのも簡単です。
• テキスト：画像に 1 行の注釈を書き込めます。色とサイズも調整可能。
• まとめて処理：ギャラリーから複数枚を共有するか、アプリで複数枚を選び、1 枚ずつ確認してからまとめて書き出せます。
• 用途の透かし：「ビザ申請専用」のような 1 行の文字を画像全体に敷き詰め、ほかの用途に使われたらすぐ分かるようにします。色・角度・透明度・密度を調整できます。
• AI review：対応機種（Pixel 9 以降など）では、端末内の Gemini Nano がページ全体をもう一度読み、文中に出てくる名前など、ルールが見落とした情報を隠します。
• チャットのスクリーンショット検出（Labs）：チャット画面のアイコン、ニックネーム、チャットのタイトルを隠します。認識は完全ではありません。
• ダークテーマはシステムに連動。テーマカラーは壁紙に連動（Android 12 以降）するほか、紫・青・ティール・緑・オレンジ・赤・ピンクから選べます。

【料金】
ダウンロード無料で、すべての機能とプライバシー保護を利用できます。無料で書き出した画像の右下には、マスクを避けて小さな「Yomark」の透かしが入ります。1 回の購入で永久に外せます。サブスクリプションではなく、有料なのはこれだけです。

【オープンソース】
ソースコード、設計資料、テスト記録は GitHub で公開しています：github.com/FlintyLemming/yomark

アプリの表示言語は英語と簡体字中国語です。Android 10 以降に対応しています。
```

## ko-KR · 한국어

说明里写明读不了韩文（谚文）：韩文的人名、地址、韩国车牌要自己拖框。

### 应用名称（25/30）

```text
Yomark - 스크린샷 개인정보 자동 가리기
```

### 简短说明（58/80）

```text
스크린샷 속 전화번호·이메일·카드 번호·얼굴·QR 코드를 자동으로 가립니다. 인터넷 없이 기기에서 처리.
```

### 完整说明（2495/4000）

```text
Yomark은 스크린샷 속 전화번호, 이메일, 카드 번호, 얼굴, QR 코드 같은 정보를 자동으로 찾아 가려 줍니다. 인식, 편집, 내보내기가 모두 휴대폰 안에서 이루어집니다. 앱에는 인터넷 권한이 없고, 카메라·저장공간·위치·연락처 등 어떤 권한도 요청하지 않습니다.

■ 개인정보 보호
• 인터넷 권한 없음: '업로드하지 않겠다'는 약속이 아니라, 시스템이 이 앱에 네트워크를 아예 열어 주지 않습니다. 비행기 모드에서도 그대로 작동합니다.
• 런타임 권한 없음: 이미지는 시스템 사진 선택 도구로 고르므로, 앱은 선택한 이미지만 받고 갤러리의 다른 사진은 볼 수 없습니다.
• 선언된 권한은 데이터를 읽지 않는 몇 가지뿐입니다. 예를 들어 Google Play 결제 라이브러리가 추가하는 BILLING은 인앱 구매에만 쓰입니다.
• 깨끗한 내보내기: 원본 해상도로 다시 그리고 다시 인코딩하며, 위치·기기 모델·촬영 시간 같은 EXIF 정보는 남기지 않습니다.
• 계정 없음, 광고 없음, 데이터 수집 없음.

■ 사용 방법
1. 이미지를 고르거나 갤러리에서 Yomark으로 공유하세요. 여러 장도 한 번에 됩니다.
2. 1~2초 만에 인식이 끝나고, 찾은 정보는 하늘색 블록으로 가려집니다. 가린 부분을 탭하면 해제되고, 가려지지 않은 글자를 탭하면 가려집니다. 놓친 곳은 한 손가락으로 끌어서 상자를 그리면 됩니다.
3. 'Export'로 갤러리에 저장하거나, 공유 버튼으로 저장하지 않고 바로 보낼 수 있습니다.

■ 인식하는 정보
기본으로 가림: 전화번호, 이메일, 카드 번호(보안 코드·유효기간 포함), IBAN, 여권 번호와 MRZ, 생년월일, 차대번호(VIN), API 키, MAC 주소, 얼굴, 읽을 수 있는 QR 코드와 바코드, 그리고 영어·중국어로 쓰인 이름과 주소.
기본으로 꺼짐: 운송장 번호·주문 번호 같은 긴 숫자, 날짜와 시간, URL, IP 주소.
종류마다 설정에서 'Mask'(가림) 또는 'Off'(끔)로 바꿀 수 있습니다.

아직 한글은 읽지 못합니다. 한글로 쓰인 이름·주소나 한국 번호판은 자동으로 가려지지 않으니, 상자를 그려 직접 가려 주세요. 숫자와 영문으로 된 정보, 얼굴, QR 코드는 언어와 관계없이 인식합니다.

글자는 앱에 함께 설치되는 PP-OCRv6 모델이 읽고, 무엇이 민감한 정보인지는 규칙이 판단합니다. 정규식과 검증 숫자(카드 번호의 Luhn 검사 등)를 쓰기 때문에 모든 인식에는 이유가 있습니다. 얼굴과 바코드는 Google ML Kit의 오프라인 모델이 찾습니다. 모든 모델이 설치 파일에 들어 있어 설치 직후부터 오프라인으로 작동합니다. 인식이 완벽하지는 않으니 공유하기 전에 한 번 확인해 주세요.

■ 다섯 가지 스타일
단색(Solid), 모자이크, 흐림, 마커, 펜. 가리는 곳마다 다른 스타일을 쓸 수 있습니다. 단색의 색(또는 주변과 비슷한 색), 모자이크 크기, 흐림 강도, 마커와 펜의 색·굵기를 조절할 수 있고, 스포이트로 이미지에서 색을 가져올 수도 있습니다. 미리보기와 내보내기는 같은 렌더러를 쓰므로 보이는 그대로 내보내집니다.
참고: 스크린샷은 글꼴이 정해져 있고 배경이 깨끗해서 모자이크와 흐림은 복원될 수 있습니다. 확실히 숨기려면 단색을 쓰세요.

■ 그 밖의 기능
• 직접 그린 상자: 네 모서리를 끌어 크기를 바꾸고, 가운데를 끌어 옮깁니다. 두 손가락으로 확대하고 이동합니다.
• Big Bang: 글자를 길게 누르면 인식된 글자가 하나씩 펼쳐져, 번호의 일부만 가리기도 쉽습니다.
• 텍스트: 이미지에 한 줄 메모를 쓸 수 있고, 색과 크기를 조절할 수 있습니다.
• 일괄 처리: 갤러리에서 여러 장을 공유하거나 앱에서 여러 장을 고른 뒤, 한 장씩 확인하고 한꺼번에 내보냅니다.
• 용도 워터마크: '비자 신청용' 같은 한 줄 문구를 이미지 전체에 깔아 두어, 다른 용도로 쓰이면 바로 알아볼 수 있게 합니다. 색, 각도, 투명도, 밀도를 조절할 수 있습니다.
• AI review: 지원 기기(Pixel 9 이후 등)에서는 휴대폰 안의 Gemini Nano가 페이지 전체를 다시 읽고, 규칙이 놓친 정보를 가립니다.
• 채팅 스크린샷 인식(Labs): 채팅 화면의 프로필 사진, 닉네임, 채팅방 이름을 가립니다. 인식이 완벽하지 않을 수 있습니다.
• 다크 테마는 시스템 설정을 따릅니다. 테마 색상은 배경화면을 따르며(Android 12 이상), 보라·파랑·청록·초록·주황·빨강·분홍 중에서 고를 수도 있습니다.

■ 요금
다운로드는 무료이며, 모든 기능과 개인정보 보호 기능을 그대로 쓸 수 있습니다. 무료로 내보낸 이미지에는 오른쪽 아래에 가린 곳을 피해 작은 'Yomark' 워터마크가 들어갑니다. 한 번 구매하면 영구적으로 없앨 수 있으며, 구독이 아닙니다. 유료 항목은 이것 하나뿐입니다.

■ 오픈 소스
소스 코드, 설계 문서, 테스트 기록을 GitHub에 공개하고 있습니다: github.com/FlintyLemming/yomark

앱 화면은 영어와 중국어(간체)로 제공됩니다. Android 10 이상에서 사용할 수 있습니다.
```

## fr-FR · Français (France)

### 应用名称（29/30）

```text
Yomark - Masquez vos captures
```

### 简短说明（77/80）

```text
Masque automatiquement numéros, e-mails, visages et QR codes. Sans connexion.
```

### 完整说明（3986/4000）

```text
Yomark repère dans vos captures d'écran les numéros de téléphone, e-mails, numéros de carte, IBAN, visages et QR codes, et les masque pour vous. Tout se passe sur votre téléphone : l'application n'a pas accès à Internet et ne demande aucune autorisation.

CONFIDENTIALITÉ
• Pas d'accès à Internet : ce n'est pas une promesse, c'est le système qui n'ouvre jamais le réseau à l'application. Elle fonctionne aussi en mode avion.
• Aucune autorisation à l'exécution : le sélecteur de photos du système ne lui transmet que les images choisies.
• Ses rares autres déclarations ne lisent aucune donnée, comme BILLING (bibliothèque Google Play Billing), utilisée pour l'achat intégré.
• Export propre : l'image est redessinée en pleine résolution, sans données EXIF (position, modèle d'appareil, date de prise de vue).
• Pas de compte, pas de publicité, aucune donnée collectée.

COMMENT ÇA MARCHE
1. Choisissez une image, ou partagez-en plusieurs vers Yomark depuis la galerie.
2. En une ou deux secondes, tout ce qui a été trouvé est recouvert de blocs bleu ciel. Touchez un masque pour le retirer, touchez un texte pour le masquer, ou tracez un cadre d'un doigt sur un oubli.
3. Touchez « Export » pour enregistrer l'image, ou partagez-la directement sans l'enregistrer.

CE QUI EST DÉTECTÉ
Masqués par défaut : téléphones, e-mails, noms, adresses, plaques d'immatriculation, numéros de carte (avec cryptogramme et date d'expiration), IBAN, numéros de pièce d'identité et de passeport, bandes MRZ, numéros de sécurité sociale américains (SSN), dates de naissance, VIN, clés d'API, adresses MAC, visages, QR codes et codes-barres lisibles.
Désactivés par défaut : longs numéros (suivi, commande…), dates et heures, URL, adresses IP.
Chaque type se règle sur « Mask » (masquer) ou « Off » (désactivé) dans les paramètres.

Le texte est lu par le modèle PP-OCRv6 intégré, et des règles avec chiffres de contrôle (comme la clé de Luhn des cartes) décident de ce qui est sensible : chaque détection a une raison. Visages et codes-barres : modèles hors ligne de Google ML Kit, inclus eux aussi.
Les noms et adresses sont surtout reconnus dans les textes en anglais et en chinois. La reconnaissance n'est pas parfaite : jetez un œil avant de partager.

CINQ STYLES
Aplat (Solid), mosaïque, flou, marqueur et stylo, au choix pour chaque masque, avec couleurs, intensité et épaisseur réglables et une pipette. L'aperçu et l'export utilisent le même rendu : vous exportez ce que vous voyez.
Attention : sur une capture d'écran, la mosaïque et le flou peuvent parfois être inversés ; pour vraiment cacher une information, utilisez l'aplat.

ET AUSSI
• Cadres manuels : tirez les coins pour redimensionner, le centre pour déplacer ; pincez pour zoomer.
• Big Bang : un appui long sur un texte étale ses caractères un par un, pour ne masquer qu'une partie d'un numéro.
• Traitement par lot : partagez ou sélectionnez plusieurs images, vérifiez-les une à une, puis exportez-les ensemble.
• Filigrane d'usage : répétez une ligne comme « Uniquement pour une demande de visa » sur toute l'image, pour qu'un autre usage saute aux yeux.
• AI review : sur les appareils compatibles (comme les Pixel 9 et suivants), Gemini Nano relit la page sur votre téléphone et masque ce que les règles ont manqué.
• Captures de discussion (Labs) : masque avatars, pseudos et titre de la discussion ; pas toujours exact.
• Thème sombre et couleur du thème : selon le système et le fond d'écran (Android 12 et ultérieur), ou au choix.

TARIF
Téléchargement gratuit, toutes fonctionnalités incluses. Les exports gratuits portent un petit filigrane « Yomark » en bas à droite, à l'écart de vos masques ; un achat unique le supprime définitivement. Pas d'abonnement : c'est le seul élément payant.

OPEN SOURCE
Code source, documents de conception et comptes rendus de tests sont publics sur GitHub : github.com/FlintyLemming/yomark

L'interface est en anglais et en chinois simplifié. Nécessite Android 10 ou version ultérieure.
```

## es-419 · Español (Latinoamérica)

### 应用名称（29/30）

```text
Yomark - Censura tus capturas
```

### 简短说明（78/80）

```text
Oculta automáticamente teléfonos, correos, rostros y códigos QR. Sin internet.
```

### 完整说明（3978/4000）

```text
Yomark encuentra en tus capturas de pantalla números de teléfono, correos, números de tarjeta, rostros, códigos QR y más, y los oculta por ti. Todo ocurre en tu teléfono: la app no tiene permiso de acceso a internet y no pide ningún otro permiso.

PRIVACIDAD
• Sin permiso de internet: no es una promesa, es que el sistema nunca le abre la red a la app. Funciona igual en modo avión.
• Sin permisos en tiempo de ejecución: el selector de fotos del sistema solo le entrega las imágenes que elijas.
• Sus pocas declaraciones adicionales no leen datos, como BILLING (biblioteca de Facturación de Google Play), que solo se usa para la compra en la app.
• Exportación limpia: la imagen se vuelve a dibujar en resolución completa, sin datos EXIF como ubicación, modelo del dispositivo o fecha de captura.
• Sin cuenta, sin anuncios, sin recopilar datos.

CÓMO FUNCIONA
1. Elige una imagen, o comparte varias a Yomark desde tu galería.
2. En uno o dos segundos, todo lo encontrado queda cubierto con bloques celestes. Toca un bloque para quitarlo, toca un texto para ocultarlo, o arrastra un recuadro con un dedo sobre lo que se haya pasado por alto.
3. Toca “Export” para guardarla en la galería, o compártela directamente sin guardarla.

QUÉ DETECTA
Ocultos de forma predeterminada: teléfonos, correos, nombres, direcciones, placas de vehículos, números de tarjeta (con código de seguridad y vencimiento), IBAN, números de identificación y de pasaporte, zonas MRZ, números de seguro social de EE. UU. (SSN), fechas de nacimiento, VIN, claves de API, direcciones MAC, rostros, y códigos QR y de barras legibles.
Desactivados de forma predeterminada: números largos (de seguimiento, de pedido…), fechas y horas, URL, direcciones IP.
Cada tipo se puede poner en “Mask” (ocultar) u “Off” (desactivado) en la configuración.

El texto lo lee el modelo PP-OCRv6 incluido en la app, y unas reglas con dígitos de control (como la verificación de Luhn de las tarjetas) deciden qué es sensible, así que cada detección tiene un motivo. Rostros y códigos de barras: modelos sin conexión de Google ML Kit, también incluidos.
Los nombres y las direcciones se detectan sobre todo en textos en inglés y en chino. El reconocimiento no es perfecto: revisa la imagen antes de compartirla.

CINCO ESTILOS
Bloque sólido (Solid), mosaico, desenfoque, marcador y pluma, a elegir para cada bloque, con colores, intensidad y grosor ajustables y un gotero. La vista previa y la exportación usan el mismo renderizado: exportas lo que ves.
Aviso: en una captura de pantalla, el mosaico y el desenfoque a veces se pueden revertir; para ocultar algo de verdad, usa el bloque sólido.

ADEMÁS
• Recuadros manuales: arrastra las esquinas para cambiar el tamaño o el centro para mover; pellizca para hacer zoom.
• Big Bang: mantén presionado un texto para ver sus caracteres uno por uno y ocultar solo una parte de un número.
• Por lotes: comparte o elige varias imágenes, revísalas una por una y expórtalas juntas.
• Marca de agua de uso: repite una línea como “Solo para trámite de visa” sobre toda la imagen, para que cualquier otro uso salte a la vista.
• AI review: en dispositivos compatibles (como Pixel 9 y posteriores), Gemini Nano vuelve a leer la página en tu teléfono y oculta lo que las reglas pasaron por alto.
• Capturas de chat (Labs): oculta avatares, apodos y el título del chat; puede no ser exacto.
• Tema oscuro y color del tema: según el sistema y el fondo de pantalla (Android 12 y posteriores), o a elección.

PRECIO
Descarga gratis, con todas las funciones. Las exportaciones gratis llevan una pequeña marca de agua “Yomark” en la esquina inferior derecha, lejos de lo que ocultaste; una compra única la quita para siempre. No es una suscripción y es lo único de pago.

CÓDIGO ABIERTO
El código fuente, los documentos de diseño y los registros de pruebas son públicos en GitHub: github.com/FlintyLemming/yomark

La interfaz está en inglés y chino simplificado. Requiere Android 10 o posterior.
```

## zh-TW · 繁體中文（台灣）

由简中转写成台湾用语；不提取件码（规则只认简体字段名「取件码」），人名、地址加一句繁体可能漏掉。

### 应用名称（11/30）

```text
有碼 - 截圖自動打碼
```

### 简短说明（51/80）

```text
截圖傳出去前先打碼：自動找出手機號碼、Email、信用卡號、人臉和 QR Code，本機辨識、不連網。
```

### 完整说明（1664/4000）

```text
截圖裡的手機號碼、Email、信用卡號、人臉和 QR Code，有碼會自動找出來並打上碼。辨識、編輯、匯出全在手機上完成：App 沒有連網權限，也不會向你要求相機、儲存空間、位置、聯絡人等任何權限。

【隱私】
• 沒有連網權限：不是承諾「不上傳」，而是系統層面就不讓這個程式連網，開著飛航模式也照常運作。
• 不要求執行階段權限：選圖走系統的相片挑選器，App 只拿得到你選的那幾張，讀不到相簿裡的其他圖片。
• 安裝檔裡只有幾項不涉及讀取資料的權限宣告，例如 Google Play 帳款服務程式庫帶進來的 BILLING，只用於應用程式內購買。
• 乾淨匯出：按原圖解析度重新繪製、重新編碼，位置、機型、拍攝時間等 EXIF 資訊一律不保留。
• 沒有帳號，沒有廣告，不收集任何資料。

【怎麼用】
1. 選一張圖，或在相簿裡把圖分享給「有碼」，一次可以分享多張。
2. 等一兩秒辨識完，認出來的蓋上天藍色塊。點一下碼就取消，點一下沒打碼的字就打上。漏掉的地方，用一根手指拖出一個框補上。
3. 點「Export」（匯出）存進相簿，或點分享按鈕直接傳出去，不存進相簿。

【辨識範圍】
預設打碼：電話、Email、姓名、地址、車牌、信用卡號（含安全碼、有效期限）、IBAN、證件號碼（含台灣身分證、香港身分證）、護照號碼與機讀碼、出生日期、車身號碼（VIN）、API 金鑰、MAC 位址、人臉、能解碼的 QR Code 和條碼。
預設關閉：物流單號、訂單編號等長數字串、日期時間、網址、IP 位址。
每一類都可以在設定裡設為「Mask」（打碼）或「Off」（關閉）。

文字由隨 App 安裝的 PP-OCRv6 模型辨識；哪些算敏感資訊交給規則判斷：正規表示式加檢查碼（例如信用卡號的 Luhn 檢查），每一處命中都說得出原因。人臉和條碼由 Google ML Kit 的離線模型辨識。模型都打包在安裝檔裡，裝好就能離線使用。
姓名、地址的辨識以簡體中文和英文為主，繁體中文的可能會漏掉；辨識不保證完全準確，傳出去前請再看一眼。

【五種樣式】
色塊、馬賽克、模糊、麥克筆、畫筆，每一塊碼可以用不同的樣式。色塊顏色（或周圍的相近色）、馬賽克粗細、模糊強度、麥克筆和畫筆的顏色、粗細都能調，還能用滴管從圖上取色。預覽和匯出用同一套渲染器，匯出的就是你看到的。
提醒：截圖的字型固定、背景乾淨，馬賽克和模糊有可能被還原。需要真正隱藏資訊時，請用色塊。

【更多功能】
• 手動補框：拖出的框可以拖四個角調大小、拖中間移位置；雙指縮放和平移圖片。
• 大爆炸（Big Bang）：長按圖上的字，辨識出的字一個個排出來，只遮號碼中間幾位也好辦。
• 文字：在圖上寫一行字做標註，顏色、字級都能調。
• 批次處理：在相簿裡一次分享多張，或在 App 裡一次選多張，逐張看過後一起匯出。
• 用途浮水印：在整張圖上平鋪一行字，例如「僅供辦理簽證使用」，被挪作他用時一眼就能認出來。顏色、角度、透明度、密度都能調。
• AI 增強辨識（AI review）：在支援的機型上（如 Pixel 9 及之後的機型），可以讓手機本機的 Gemini Nano 把整頁文字再讀一遍，補上規則沒認出的，例如話裡提到的人名。
• 辨識聊天記錄（Labs）：遮住聊天截圖裡的頭像、暱稱和聊天標題，不保證辨識完全準確。
• 深色模式預設跟隨系統；主題色預設跟隨桌布（Android 12 以上），也可以換成紫、藍、青、綠、橙、紅、粉其中一種。

【關於付費】
下載免費，所有功能都能用，隱私保護能力一項不缺。免費匯出的圖片右下角會帶一個「Yomark」小浮水印，自動避開打碼區域；一次買斷即可去除，永久有效，不是訂閱。這是唯一的付費項目。

【開放原始碼】
原始碼、設計文件和測試記錄都公開在 GitHub：github.com/FlintyLemming/yomark

介面提供英文和簡體中文，可在設定中切換。支援 Android 10 以上版本。
```

## zh-HK · 中文（香港）

在台湾版上换成香港用语（私隱、網絡、識別、電郵、身份證……）。

### 应用名称（11/30）

```text
有碼 - 截圖自動打碼
```

### 简短说明（49/80）

```text
截圖傳出去前先打碼：自動找出手機號碼、電郵、信用卡號、人臉和 QR Code，本機識別、毋須上網。
```

### 完整说明（1657/4000）

```text
截圖裡的手機號碼、電郵、信用卡號、人臉和 QR Code，有碼會自動找出來並打上碼。識別、編輯、匯出全在手機上完成：App 沒有網絡權限，也不會向你要求相機、儲存空間、位置、聯絡人等任何權限。

【私隱】
• 沒有網絡權限：不是承諾「不上傳」，而是系統層面就不讓這個程式上網，開著飛行模式也照常運作。
• 不要求運行時權限：選圖走系統的相片選擇器，App 只拿得到你選的那幾張，讀不到相簿裡的其他圖片。
• 安裝檔裡只有幾項不涉及讀取資料的權限宣告，例如 Google Play 結算服務程式庫帶進來的 BILLING，只用於應用程式內購買。
• 乾淨匯出：按原圖解像度重新繪製、重新編碼，位置、機型、拍攝時間等 EXIF 資訊一律不保留。
• 沒有帳戶，沒有廣告，不收集任何資料。

【怎麼用】
1. 選一張圖，或在相簿裡把圖分享給「有碼」，一次可以分享多張。
2. 等一兩秒識別完，認出來的蓋上天藍色塊。點一下碼就取消，點一下沒打碼的字就打上。漏掉的地方，用一根手指拖出一個框補上。
3. 點「Export」（匯出）存進相簿，或點分享按鈕直接傳出去，不存進相簿。

【識別範圍】
預設打碼：電話、電郵、姓名、地址、車牌、信用卡號（含安全碼、有效期限）、IBAN、證件號碼（含台灣身份證、香港身份證）、護照號碼與機讀碼、出生日期、車身號碼（VIN）、API 金鑰、MAC 位址、人臉、能解碼的 QR Code 和條碼。
預設關閉：速遞單號、訂單編號等長數字串、日期時間、網址、IP 位址。
每一類都可以在設定裡設為「Mask」（打碼）或「Off」（關閉）。

文字由隨 App 安裝的 PP-OCRv6 模型識別；哪些算敏感資訊交給規則判斷：正規表達式加檢查碼（例如信用卡號的 Luhn 檢查），每一處命中都說得出原因。人臉和條碼由 Google ML Kit 的離線模型識別。模型都打包在安裝檔裡，裝好就能離線使用。
姓名、地址的識別以簡體中文和英文為主，繁體中文的可能會漏掉；識別不保證完全準確，傳出去前請再看一眼。

【五種樣式】
色塊、馬賽克、模糊、螢光筆、畫筆，每一塊碼可以用不同的樣式。色塊顏色（或周圍的相近色）、馬賽克粗細、模糊強度、螢光筆和畫筆的顏色、粗細都能調，還能用滴管從圖上取色。預覽和匯出用同一套渲染器，匯出的就是你看到的。
提醒：截圖的字型固定、背景乾淨，馬賽克和模糊有可能被還原。需要真正隱藏資訊時，請用色塊。

【更多功能】
• 手動補框：拖出的框可以拖四個角調大小、拖中間移位置；雙指縮放和平移圖片。
• 大爆炸（Big Bang）：長按圖上的字，識別出的字一個個排出來，只遮號碼中間幾位也好辦。
• 文字：在圖上寫一行字做標註，顏色、字體大小都能調。
• 批次處理：在相簿裡一次分享多張，或在 App 裡一次選多張，逐張看過後一起匯出。
• 用途水印：在整張圖上平鋪一行字，例如「僅供辦理簽證使用」，被挪作他用時一眼就能認出來。顏色、角度、透明度、密度都能調。
• AI 增強識別（AI review）：在支援的機型上（如 Pixel 9 及之後的機型），可以讓手機本機的 Gemini Nano 把整頁文字再讀一遍，補上規則沒認出的，例如話裡提到的人名。
• 識別聊天記錄（Labs）：遮住聊天截圖裡的頭像、暱稱和聊天標題，不保證識別完全準確。
• 深色模式預設跟隨系統；主題色預設跟隨牆紙（Android 12 以上），也可以換成紫、藍、青、綠、橙、紅、粉其中一種。

【關於付費】
下載免費，所有功能都能用，私隱保護能力一項不缺。免費匯出的圖片右下角會帶一個「Yomark」小水印，自動避開打碼區域；一次買斷即可去除，永久有效，不是訂閱。這是唯一的付費項目。

【開放原始碼】
原始碼、設計文件和測試記錄都公開在 GitHub：github.com/FlintyLemming/yomark

介面提供英文和簡體中文，可在設定中切換。支援 Android 10 以上版本。
```

## 机器翻译

在「扩大用户群 › 翻译 › 商品详情和商品」下免费机器翻译的订单，源语言只能选英语。直接拿 en-US 商品详情去翻，
出来的标题大多超过 30 字、简短说明 85–136 字、完整说明 4000–5100 字，放不进上限（2026-10-23 第一单 ff1936bd1v2，没有应用）。
所以第二单（486926bd1v2）在下单页把源文换成下面这份压缩版：标题照旧，简短说明 48 字，完整说明 2701 字。
翻出来简短说明 42–78 字、完整说明 2382–3747 字，都放得下；标题机翻常把 “Redact” 译成「编辑」「删除」，23 种都换成了下表里手写的。
商品详情和一次性商品在同一单里翻，逐种语言「查看并应用」。

### 源文 · 简短说明（48/80）

```text
Hide phone numbers, faces and QR codes. Offline.
```

### 源文 · 完整说明（2701/4000）

```text
Yomark finds the phone numbers, addresses, names, emails, faces and QR codes in your screenshots and masks them for you. Everything happens on your phone: the app has no internet permission and asks for no other permission.

PRIVACY
• No internet permission: the system never lets the app online, so it works the same in airplane mode.
• No runtime permissions: the system photo picker gives the app only the images you choose.
• Its few other declarations read no data, such as BILLING from Google Play Billing, used only for the in-app purchase.
• Clean export: images are redrawn at full resolution without EXIF data such as location or device model.
• No account, no ads, no data collected.

HOW IT WORKS
1. Pick an image, or share several to Yomark from your gallery.
2. In a second or two, everything found is covered with sky-blue blocks. Tap a mask to remove it, tap text to mask it, or drag a box over anything it missed.
3. Tap Export to save, or Share to send it without saving.

WHAT IT FINDS
Masked by default: phone numbers, emails, names, addresses, license plates, card numbers, IBANs, ID and passport numbers, US Social Security numbers, dates of birth, VINs, API keys, MAC addresses, faces, QR codes and barcodes.
Off by default: long numbers such as tracking and order numbers, dates and times, URLs, IP addresses.
Each kind can be set to Mask or Off in Settings.
Text is read by an OCR model bundled with the app and checked by rules on your phone. Names and addresses are recognized in English and Chinese text. Recognition isn't perfect, so check before you share.

FIVE STYLES
Solid, Mosaic, Blur, Marker and Pen, with adjustable colors, strength and width. What you see is what you export.
Mosaic and blur can sometimes be reversed on screenshots; to truly hide something, use Solid.

MORE
• Manual boxes: drag the corners to resize or the middle to move.
• Big Bang: long-press text to pick single characters, handy for masking part of a number.
• Batch: open several images, check each one, then export them together.
• Purpose watermark: tile a line such as "For visa application only" across the image.
• AI review: on supported devices (such as Pixel 9 and later), on-device Gemini Nano reads the page again and masks what the rules missed.
• Detect chat screenshots (Labs): masks avatars, nicknames and the chat title; may not be fully accurate.

PRICING
Free to download with every feature. Free exports carry a small "Yomark" watermark in the corner; a one-time purchase removes it for good. No subscription.

OPEN SOURCE
Source code is public on GitHub: github.com/FlintyLemming/yomark

The interface is in English and Simplified Chinese. Requires Android 10 or later.
```

### 标题

| 语言 | 应用名称 |
|---|---|
| ar | Yomark - إخفاء بيانات اللقطات（29） |
| cs-CZ | Yomark - Začernění snímků（25） |
| da-DK | Yomark - Slør skærmbilleder（27） |
| de-DE | Yomark - Screenshots schwärzen（30） |
| es-ES | Yomark - Censura tus capturas（29） |
| fi-FI | Yomark - Sensuroi kaappaukset（29） |
| hi-IN | Yomark - स्क्रीनशॉट सेंसर करें（30） |
| hu-HU | Yomark - Adatok kitakarása（26） |
| id | Yomark - Sensor Screenshot（26） |
| it-IT | Yomark - Oscura gli screenshot（30） |
| iw-IL | Yomark - השחרת צילומי מסך（25） |
| nl-NL | Yomark - Screenshots maskeren（29） |
| no-NO | Yomark - Sladd skjermbilder（27） |
| pl-PL | Yomark - Zamazywanie zrzutów（28） |
| pt-BR | Yomark - Censure seus prints（28） |
| ro | Yomark - Cenzurează capturi（27） |
| ru-RU | Yomark - Цензура скриншотов（27） |
| sk | Yomark - Začiernenie snímok（27） |
| sv-SE | Yomark - Maskera skärmdumpar（28） |
| th | Yomark - เซ็นเซอร์ภาพหน้าจอ（27） |
| tr-TR | Yomark - Görüntü Sansürleme（27） |
| uk | Yomark - Цензура скріншотів（27） |
| vi | Yomark - Che ảnh chụp màn hình（30） |

## 一次性商品「去除水印」

商品 ID `yomark_remove_watermark`。说明不少于 50 个字才能在所有展示位推广（Play 的提示），简中这条是 34 个字，没改。

| 语言 | 名称 | 说明 |
|---|---|---|
| zh-CN | 去除水印 | 一次付费，永久有效。去除导出图片右下角的「有码 Yomark」水印。 |
| en-US | Remove watermark | One-time purchase, yours for good. Removes the small "Yomark" watermark from the bottom-right corner of exported images. Not a subscription. |
| ja-JP | 透かしを削除 | 1 回の購入でずっと有効です。書き出した画像の右下に入る小さな「Yomark」の透かしを消します。サブスクリプションではありません。 |
| ko-KR | 워터마크 제거 | 한 번 구매하면 계속 쓸 수 있습니다. 내보낸 이미지 오른쪽 아래의 작은 'Yomark' 워터마크를 없앱니다. 구독이 아닙니다. |
| fr-FR | Supprimer le filigrane | Achat unique, valable pour toujours. Supprime le petit filigrane « Yomark » en bas à droite des images exportées. Ce n'est pas un abonnement. |
| es-419 | Quitar la marca de agua | Compra única, para siempre. Quita la pequeña marca de agua “Yomark” de la esquina inferior derecha de las imágenes exportadas. No es una suscripción. |
| zh-TW | 去除浮水印 | 一次付費，永久有效，不是訂閱。去除匯出圖片右下角的「Yomark」小浮水印，其他功能本來就全部免費。 |
| zh-HK | 去除水印 | 一次付款，永久有效，並非訂閱。去除匯出圖片右下角的「Yomark」小水印，其他功能本身已經全部免費。 |

机翻的 23 种跟着商品详情一起翻（源文是上表 en-US 那条），不在这里列。
