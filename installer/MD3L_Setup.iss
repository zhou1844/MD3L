; MD3L Launcher Installer - Inno Setup Script
; 中文安装向导，默认安装到 D:\MD3L

#define AppName "MD3L 启动器"
#ifndef AppVersion
  #define AppVersion "1.3.5"
#endif
#define AppPublisher "MD3L"
#define AppExeName "MD3L.exe"
#define SourceDir "..\dist"

[Setup]
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL=https://gitee.com/foolish-bird-crossing/md3llauncher
AppSupportURL=https://gitee.com/foolish-bird-crossing/md3llauncher
AppUpdatesURL=https://gitee.com/foolish-bird-crossing/md3llauncher
DefaultDirName=D:\MD3L
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=MD3L_Setup
SetupIconFile=..\src\main\resources\app_icon.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ShowLanguageDialog=no
LanguageDetectionMethod=none
UninstallDisplayIcon={app}\{#AppExeName}
UninstallDisplayName={#AppName}
CloseApplications=yes
RestartIfNeededByRun=no
DisableWelcomePage=no
DisableDirPage=no
DisableReadyPage=no

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[CustomMessages]
chinesesimp.WelcomeLabel1=欢迎安装 [name]
chinesesimp.WelcomeLabel2=本向导将引导您完成 [name] 的安装。%n%n建议您在继续前关闭所有其他应用程序。
chinesesimp.SelectDirLabel3=请选择安装目录，然后点击"下一步"继续。
chinesesimp.SelectDirBrowseLabel=若要选择其他文件夹，请点击"浏览"。
chinesesimp.ReadyLabel1=安装程序已准备好在您的计算机上安装 [name]。
chinesesimp.ReadyLabel2a=点击"安装"继续，或点击"上一步"重新查看或更改任何设置。
chinesesimp.FinishedHeadingLabel=[name] 安装完成
chinesesimp.FinishedLabel=安装程序已完成 [name] 的安装。%n%n您现在可以运行应用程序了。

[Tasks]
Name: "desktopicon"; Description: "在桌面创建快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked
Name: "startmenuicon"; Description: "在开始菜单创建快捷方式"; GroupDescription: "附加任务:"; Flags: checkedonce

[Files]
Source: "{#SourceDir}\{#AppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: startmenuicon
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "立即运行 {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\data"

[Code]
// 安装前检查是否已有旧版本正在运行
function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  Result := True;
  // 尝试关闭已运行的实例
  Exec('taskkill', '/F /IM MD3L.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

// 卸载前提示用户是否保留游戏数据
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  MsgResult: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    MsgResult := MsgBox(
      '是否同时删除游戏数据（启动器设置、账号信息、崩溃日志等）？' + #13#10 +
      '选择"否"将保留 data 文件夹中的数据。',
      mbConfirmation, MB_YESNO
    );
    if MsgResult = IDYES then
    begin
      DelTree(ExpandConstant('{app}\data'), True, True, True);
    end;
  end;
end;
