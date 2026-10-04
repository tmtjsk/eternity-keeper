; Eternity Keeper's Windows installer (Inno Setup 6).
;
; Built by tools\release\build-release.ps1 out of the same staged folder the
; zip is made from, so the two always hold the same files:
;
;   ISCC /DAppVersion=1.0.0-beta /DStage="...\target\release\Eternity Keeper"
;        /DOutput="...\target\release" tools\release\installer.iss
;
; What it does that the zip cannot: a Start menu entry, an entry under
; "Installed apps" with an uninstaller, and an upgrade that clears out the
; last version's program files first. It needs no administrator: by default it
; installs for the current user, under %LOCALAPPDATA%\Programs.

#ifndef AppVersion
  #error AppVersion is not defined. Build with tools\release\build-release.ps1.
#endif
#ifndef Stage
  #error Stage is not defined. Build with tools\release\build-release.ps1.
#endif
#ifndef Output
  #define Output "."
#endif

#define AppName "Eternity Keeper"
#define AppExe "Eternity Keeper.exe"

; Inno Setup 6.3 named the architecture that also covers x64 emulation on Arm;
; before it there is only "x64".
#if Ver >= EncodeVer(6, 3, 0)
  #define X64 "x64compatible"
#else
  #define X64 "x64"
#endif

[Setup]
; Never change this: it is how an upgrade finds the version already installed.
AppId={{6C0E3B7F-6E9B-4B5E-9C61-3E1F6C0C5A41}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Eternity Keeper contributors
AppPublisherURL=https://github.com/tmtjsk/eternity-keeper
AppSupportURL=https://github.com/tmtjsk/eternity-keeper/issues
AppCopyright=GNU General Public License v3 or later
VersionInfoVersion=1.0.0.0
VersionInfoDescription={#AppName} setup

; For the current user unless they ask otherwise, so no administrator prompt.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={code:DefaultDir}
DisableProgramGroupPage=yes
UsePreviousAppDir=yes

; The Java runtime and the browser it ships are 64-bit, and the game-data
; reader needs Windows 10.
ArchitecturesAllowed={#X64}
ArchitecturesInstallIn64BitMode={#X64}
MinVersion=10.0

; The launcher's own mutex (pom.xml, singleInstance): setup and the
; uninstaller ask for the editor to be closed rather than failing on its
; open files.
AppMutex=EternityKeeper

SetupIconFile=..\..\src\main\launcher\eternity-keeper.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
OutputDir={#Output}
OutputBaseFilename=EternityKeeper-{#AppVersion}-win64-setup

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; An upgrade: the last version's program files go first, or a file this
; version no longer ships would stay beside the ones it does. Only where an
; earlier install is, never in a folder that merely has a "lib" of its own.
; Nothing of the user's lives here: settings, the log, backups and the game
; data the editor read are all under %APPDATA%\Eternity Keeper.
Type: filesandordirs; Name: "{app}\ui"; Check: IsEarlierInstall
Type: filesandordirs; Name: "{app}\lib"; Check: IsEarlierInstall
Type: filesandordirs; Name: "{app}\jre"; Check: IsEarlierInstall
Type: filesandordirs; Name: "{app}\gamedata"; Check: IsEarlierInstall

[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Code]
function IsEarlierInstall: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\eternity-keeper.jar'));
end;

// The launcher and the Java 8 runtime it starts read their own paths in the
// computer's code page (its "language for non-Unicode programs"), so they
// cannot start from a folder whose name has letters outside it. Measured: a
// folder named in Cyrillic or Japanese on a Central European Windows, where
// the launcher reports that the jre folder is missing. The user's own
// folders are another matter -- settings, the log and temporary files under a
// profile named that way all work -- so only the install folder is checked.
function InCodePage(const Path: String): Boolean;
begin
  Result := String(AnsiString(Path)) = Path;
end;

// Under the user's own Programs folder, unless their profile's name puts
// that out of the launcher's reach.
function DefaultDir(Param: String): String;
begin
  Result := ExpandConstant('{autopf}\{#AppName}');
  if not InCodePage(Result) then
    Result := ExpandConstant('{sd}\{#AppName}');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = wpSelectDir) and (not InCodePage(WizardDirValue)) then
  begin
    MsgBox('Eternity Keeper cannot start from this folder. Its path has letters outside this computer''s language for non-Unicode programs, and the Java runtime the editor ships with cannot read them.'
      + #13#10#13#10 + 'Choose a folder with plain letters in its path, for example '
      + ExpandConstant('{sd}\{#AppName}') + '.', mbError, MB_OK);
    Result := False;
  end;
end;

// The uninstaller removes the program and nothing of the user's: the
// settings, and above all the backups of their saves, stay until they
// delete them themselves.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Data: String;
begin
  if (CurUninstallStep = usPostUninstall) and (not UninstallSilent) then
  begin
    Data := ExpandConstant('{userappdata}\{#AppName}');
    if DirExists(Data) then
      MsgBox('Your settings and the backups of your saves were left where they are:'
        + #13#10#13#10 + Data + #13#10#13#10
        + 'Delete that folder yourself if you no longer want them.'
        , mbInformation, MB_OK);
  end;
end;
