# MSC Launcher Metro

Это отдельная Windows 8.1-совместимая desktop-редакция лаунчера с полноэкранной
оболочкой в стиле приложений Windows Store 8.1. У неё нет обычного заголовка,
рамки окна и области изменения размера; оболочка занимает весь выбранный экран.
Обычная редакция и её установщик не заменяются.

Metro-редакция имеет:

- отдельную точку входа `MetroMain`;
- отдельный полноэкранный режим Store-style;
- скрывающуюся верхнюю системную панель с кнопкой закрытия;
- отдельный каталог `%APPDATA%\msc-launcher-metro-data`;
- отдельный AppId, каталог установки и ярлыки;
- отдельные артефакты обновления с `Metro` в имени.
- регистрацию `msc-launcher://` при запуске portable/bundled companion.

При первом запуске пользовательские данные копируются из обычной редакции.
Последующие изменения двух редакций сохраняются независимо.

## Сборка

Портативный JAR и ZIP (нужна установленная Java 8 или новее):

```powershell
.\gradlew.bat metroAssemble
```

Полный установщик с Java 8 и JavaFX:

```powershell
.\gradlew.bat metroWindowsInstaller `
  -PbundledJavaHome=C:\Java\LibericaJRE8Full `
  -PinnoSetupCompiler="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

Для обновлений Metro-артефакты релиза должны называться, например,
`MSC-Launcher-Metro-2.4.0.jar` и `MSC-Launcher-Metro-2.4.0-Setup.exe`.

Настоящая Windows 8.1 APPX-оболочка находится в `metro-appx`. Как и Steam Tile,
она работает в AppContainer, создаёт secondary/live tiles и передаёт команды
desktop companion через зарегистрированный протокол `msc-launcher://`. Сам Java
и Minecraft остаются в companion, потому что AppContainer не может запускать их
напрямую.
