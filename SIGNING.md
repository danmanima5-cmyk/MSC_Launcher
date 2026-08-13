# Подпись Windows-релиза

Для отображения подтверждённого издателя другим пользователям нужен действующий
Authenticode Code Signing-сертификат от доверенного Windows центра сертификации.
Самоподписанный сертификат для публичного релиза не подходит.

Сертификат должен быть выпущен на юридически подтверждённое имя
`Meine Starten Corporation`. Именно имя Subject сертификата, а не строка в исходном
коде, отображается Windows как проверенный издатель.

Секреты не должны храниться в репозитории или передаваться параметрами Gradle.

## Сертификат из Windows Certificate Store

```powershell
$env:MSC_CODESIGN_SHA1 = 'ОТПЕЧАТОК_СЕРТИФИКАТА'
.\gradlew.bat signWindowsInstaller -PbundledJavaHome='C:\Java\jre8-full'
```

## Сертификат в PFX

```powershell
$env:MSC_CODESIGN_PFX = 'C:\secure\msc-launcher-code-signing.pfx'
$env:MSC_CODESIGN_PASSWORD = 'пароль-PFX'
.\gradlew.bat signWindowsInstaller -PbundledJavaHome='C:\Java\jre8-full'
```

Результат: `build/installer/MSC-Launcher-<версия>-Setup.exe`.
Задача подписывает файл с SHA-256, добавляет RFC 3161 timestamp и затем запускает
`signtool verify /pa /all /tw`. При отсутствии сертификата, timestamp или корректной
цепочки доверия сборка завершается ошибкой.

Публиковать на GitHub нужно именно проверенный EXE после выполнения этой задачи.
