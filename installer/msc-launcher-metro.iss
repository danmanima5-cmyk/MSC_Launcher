#define AppName "MSC Launcher Metro"
#define AppVersion "3.0.0"
#define AppPublisher "Meine Starten Corporation"
#define AppURL "https://github.com/danmanima5-cmyk/MSC_Launcher"
#define AppIdValue "{{D3DA44C2-8CC5-4F6E-A6D3-67F9BB54A408}"

#ifndef SourceDir
  #define SourceDir "..\build\bundled\msc-launcher-metro"
#endif

[Setup]
AppId={#AppIdValue}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}/issues
AppUpdatesURL={#AppURL}/releases/latest
DefaultDirName={localappdata}\Programs\MSC Launcher Metro
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\build\installer
OutputBaseFilename=MSC-Launcher-Metro-{#AppVersion}-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
UninstallDisplayName={#AppName}

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\lib\MSC-Launcher-Metro-{#AppVersion}.jar"""; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\lib\MSC-Launcher-Metro-{#AppVersion}.jar"""; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Создать плитку-ярлык Metro на рабочем столе"; GroupDescription: "Ярлыки:"; Flags: unchecked

[Registry]
Root: HKCU; Subkey: "Software\Classes\msc-launcher"; ValueType: string; ValueName: ""; ValueData: "URL:MSC Launcher Metro"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\msc-launcher"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\msc-launcher\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\runtime\bin\javaw.exe,0"
Root: HKCU; Subkey: "Software\Classes\msc-launcher\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\runtime\bin\javaw.exe"" -jar ""{app}\lib\MSC-Launcher-Metro-{#AppVersion}.jar"" ""%1"""

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\lib\MSC-Launcher-Metro-{#AppVersion}.jar"""; WorkingDir: "{app}"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent
