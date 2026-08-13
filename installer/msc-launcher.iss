#define AppName "MSC Launcher"
#define AppVersion "3.0.0"
#define AppPublisher "Meine Starten Corporation"
#define AppURL "https://github.com/danmanima5-cmyk/MSC_Launcher"
#define AppIdValue "{{E92AD8F8-465D-4EB6-9369-5D2C80DA6E41}"

#ifndef SourceDir
  #define SourceDir "..\build\bundled\minecraft-launcher"
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
DefaultDirName={code:GetInstallDir}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\build\installer
OutputBaseFilename=MSC-Launcher-{#AppVersion}-Setup
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
; The in-app updater exits the launcher before the installer copies files.
; Do not defer replacement of the bundled javaw.exe until reboot: a partial or
; interrupted deferred replacement can leave a shortcut pointing at an invalid
; executable and Windows then reports that the app cannot run on this PC.
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-cp ""{app}\lib\*"" Main"; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-cp ""{app}\lib\*"" Main"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Ярлыки:"; Flags: unchecked

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-cp ""{app}\lib\*"" Main"; WorkingDir: "{app}"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent

[Code]
function FindLegacyInstallInRoot(Root: Integer; var FoundDir: String): Boolean;
var
  Keys: TArrayOfString;
  BaseKey, DisplayName, InstallLocation: String;
  I: Integer;
begin
  Result := False;
  BaseKey := 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  if not RegGetSubkeyNames(Root, BaseKey, Keys) then
    Exit;

  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    DisplayName := '';
    if RegQueryStringValue(Root, BaseKey + '\' + Keys[I], 'DisplayName', DisplayName) and
       (Pos('MSC Launcher', DisplayName) > 0) then
    begin
      InstallLocation := '';
      if RegQueryStringValue(Root, BaseKey + '\' + Keys[I], 'InstallLocation', InstallLocation) and
         (InstallLocation <> '') and DirExists(InstallLocation) then
      begin
        FoundDir := RemoveBackslashUnlessRoot(InstallLocation);
        Result := True;
        Exit;
      end;
    end;
  end;
end;

function FindLegacyInstall(var FoundDir: String): Boolean;
begin
  { Covers per-user, 64-bit machine-wide and old 32-bit installers. }
  Result := FindLegacyInstallInRoot(HKCU, FoundDir) or
            FindLegacyInstallInRoot(HKLM64, FoundDir) or
            FindLegacyInstallInRoot(HKLM32, FoundDir);
end;

function GetInstallDir(Param: String): String;
var
  LegacyDir: String;
begin
  if FindLegacyInstall(LegacyDir) then
    Result := LegacyDir
  else
    Result := ExpandConstant('{localappdata}\Programs\MSC Launcher');
end;
