<#
.SYNOPSIS
Installs the release the way a player would, starts it, and uninstalls it.

.DESCRIPTION
The installer is the first thing of the editor a new user runs, on a machine
that has none of what the development setup has. So this checks it there: a
silent install for the current user into a folder with a space in its name,
then

    - every part is in place (launcher, jar, pages, browser, Java, reader);
    - the Start menu entry points at the launcher, and Windows lists the
      editor under Installed apps;
    - the Java runtime it ships starts, and the game-data reader can decode
      textures;
    - the installed editor starts and draws its page -- asked over the
      embedded browser's DevTools port -- with its settings and log in the
      data folder it was given, not in the install folder;
    - the uninstaller removes all of it and leaves the data folder alone.

It installs into, and removes, a folder of its own under %TEMP%. If Eternity
Keeper is already installed for this user it stops rather than touch that:
both would share one entry under Installed apps.

.EXAMPLE
pwsh tools\release\test-installer.ps1 -Setup target\release\EternityKeeper-1.0.0-beta-win64-setup.exe
#>
param(
	[Parameter(Mandatory)][string]$Setup,
	# The embedded browser's DevTools port the installed editor is started with.
	[int]$Port = 13777,
	# Install, check the files and uninstall, without starting the editor.
	[switch]$SkipLaunch
)

$ErrorActionPreference = 'Stop'
$appId = '{6C0E3B7F-6E9B-4B5E-9C61-3E1F6C0C5A41}_is1'
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$appId"

function Step ($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail ($text) {
	# An annotation as well: a run's log needs a GitHub sign-in to read.
	if ($env:GITHUB_ACTIONS) { Write-Host "::error title=Installer test::$($text -replace "`r?`n", ' ')" }
	Write-Host "ERROR: $text" -ForegroundColor Red
	exit 1
}

function Stop-Installed ($folder) {
	Get-CimInstance Win32_Process |
		Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($folder, [StringComparison]::OrdinalIgnoreCase) } |
		ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

$Setup = (Resolve-Path $Setup -ErrorAction SilentlyContinue).Path
if (-not $Setup) { Fail 'No installer at the path given.' }
if (Test-Path $uninstallKey) {
	Fail "Eternity Keeper is already installed for this user ($((Get-ItemProperty $uninstallKey).InstallLocation)). This test installs and uninstalls its own copy, and would take that entry with it: run it on a machine without one."
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('ek-installer-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$app = Join-Path $root 'Eternity Keeper'
$data = Join-Path $root 'data'
$log = Join-Path $root 'install.log'
New-Item -ItemType Directory -Force $root | Out-Null
$shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Eternity Keeper.lnk'
$failed = $null

try {
	# --- Install ---------------------------------------------------------------
	Step "Installing into $app"
	$install = Start-Process $Setup -Wait -PassThru -ArgumentList @(
		'/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', "/DIR=`"$app`"", "/LOG=`"$log`"")
	if ($install.ExitCode -ne 0) {
		$tail = if (Test-Path $log) { (Get-Content $log -Tail 15) -join "`n" } else { '(no log)' }
		throw "The installer exited with $($install.ExitCode).`n$tail"
	}

	foreach ($part in 'Eternity Keeper.exe', 'eternity-keeper.jar', 'ui\index.html', 'jre\bin\java.exe',
			'lib\native\win64\libcef.dll', 'lib\native\win64\jcef_helper.exe',
			'gamedata\extract_gamedata.exe', 'README.txt', 'LICENSE', 'unins000.exe') {
		if (-not (Test-Path (Join-Path $app $part))) { throw "Not installed: $part" }
	}

	if (-not (Test-Path $shortcut)) { throw "No Start menu entry at $shortcut" }
	$target = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut).TargetPath
	if ($target -ne (Join-Path $app 'Eternity Keeper.exe')) { throw "The Start menu entry points at '$target'." }

	if (-not (Test-Path $uninstallKey)) { throw 'Windows does not list the editor under Installed apps.' }
	$entry = Get-ItemProperty $uninstallKey
	if ($entry.DisplayName -ne 'Eternity Keeper' -or -not $entry.DisplayVersion -or -not $entry.UninstallString) {
		throw "The Installed apps entry is incomplete: $($entry | Out-String)"
	}
	Step "Installed: $($entry.DisplayName) $($entry.DisplayVersion)"

	# --- What it ships runs on its own ------------------------------------------
	$java = & (Join-Path $app 'jre\bin\java.exe') -version 2>&1 | Out-String
	if ($java -notmatch 'version "1\.8\.') { throw "The Java runtime it ships did not start as Java 8:`n$java" }

	$selfTest = & (Join-Path $app 'gamedata\extract_gamedata.exe') --self-test 2>&1 | Out-String
	if ($LASTEXITCODE -ne 0) { throw "The game-data reader it ships cannot decode textures:`n$selfTest" }

	# --- The installed editor starts ----------------------------------------------
	if (-not $SkipLaunch) {
		Step 'Starting the installed editor'
		# Launch4j reads extra JVM options from an .ini beside the launcher; each
		# is passed as written, so one with a path in it is quoted.
		$ini = Join-Path $app 'Eternity Keeper.l4j.ini'
		Set-Content -Path $ini -Encoding ascii -Value @("`"-Dek.data=$data`"", "-Dek.debugPort=$Port")
		Start-Process (Join-Path $app 'Eternity Keeper.exe')

		$page = $null
		$deadline = (Get-Date).AddSeconds(120)
		while (-not $page -and (Get-Date) -lt $deadline) {
			Start-Sleep -Seconds 2
			try {
				$page = Invoke-RestMethod "http://127.0.0.1:$Port/json/list" -TimeoutSec 3 |
					Where-Object { $_.url -like 'file:///*/ui/index.html' } | Select-Object -First 1
			} catch { }
		}

		if (-not $page) {
			$editorLog = Join-Path $data 'eternity.log'
			$tail = if (Test-Path $editorLog) { (Get-Content $editorLog -Tail 20) -join "`n" } else { '(no eternity.log was written)' }
			throw "The installed editor did not draw its page within two minutes.`n$tail"
		}

		Step "The editor drew $($page.url)"
		$expected = ([Uri](Join-Path $app 'ui\index.html')).AbsoluteUri
		if ($page.url -ne $expected) { throw "It loaded its page from '$($page.url)', not from the install folder ($expected)." }
		if (-not (Test-Path (Join-Path $data 'eternity.log'))) { throw "It did not keep its log in the data folder it was given ($data)." }

		Stop-Installed $app
		Start-Sleep -Seconds 3
		Remove-Item $ini -Force
		foreach ($stray in 'settings.json', 'eternity.log', 'cef.log') {
			if (Test-Path (Join-Path $app $stray)) { throw "The editor wrote $stray into its install folder." }
		}
	}

	# --- Uninstall ---------------------------------------------------------------
	Step 'Uninstalling'
	Start-Process (Join-Path $app 'unins000.exe') -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
	$deadline = (Get-Date).AddSeconds(90)
	while ((Test-Path $app) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }

	if (Test-Path $app) {
		$left = (Get-ChildItem $app -Recurse -File | Select-Object -First 10 | ForEach-Object { $_.FullName.Substring($app.Length + 1) }) -join ', '
		throw "The uninstaller left files behind: $left"
	}
	if (Test-Path $shortcut) { throw 'The uninstaller left the Start menu entry.' }
	if (Test-Path $uninstallKey) { throw 'The uninstaller left the Installed apps entry.' }
	if (-not $SkipLaunch -and -not (Test-Path (Join-Path $data 'settings.json')) -and -not (Test-Path (Join-Path $data 'eternity.log'))) {
		throw 'The uninstaller removed the data folder, which holds the user''s settings and backups.'
	}
} catch {
	$failed = $_.Exception.Message
} finally {
	# Whatever happened, nothing of the test's stays installed.
	Stop-Installed $app
	if (Test-Path (Join-Path $app 'unins000.exe')) {
		Start-Process (Join-Path $app 'unins000.exe') -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
		Start-Sleep -Seconds 5
	}
	Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failed) { Fail $failed }
Step 'The installer installs, the editor starts from it, and the uninstaller removes it.'
