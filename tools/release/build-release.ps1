<#
.SYNOPSIS
Builds the Windows release: target\release\EternityKeeper-<version>-win64.zip.

.DESCRIPTION
Everything a player needs in one folder, so nothing has to be installed:

    Eternity Keeper\
        Eternity Keeper.exe     the launcher (Launch4j, built by mvn -Pwin64)
        eternity-keeper.jar     the editor
        ui\                     its pages, loaded from disk
        lib\native\win64\       the embedded browser (JCEF, Chrome 45)
        jre\                    the Java 8 runtime it starts with
        gamedata\               the game-data reader, frozen with PyInstaller
        README.txt, LICENSE, CHANGELOG.md, THIRD-PARTY-NOTICES.md

Needs: a Java 8 JDK whose folder has a jre\ inside (Temurin 8 does), Maven,
and Python with the packages in tools\gamedata\requirements.txt.

.EXAMPLE
pwsh tools\release\build-release.ps1
pwsh tools\release\build-release.ps1 -Jdk C:\jdk8 -SkipTests
#>
param(
	# The JDK to build with and whose jre\ ships. Defaults to $env:EK_JDK, then
	# the one the development setup keeps beside the checkout.
	[string]$Jdk = $(if ($env:EK_JDK) { $env:EK_JDK } else { Join-Path $PSScriptRoot '..\..\..\tools\jdk8u492-b09' }),
	[string]$Python = $(if ($env:EK_PYTHON) { $env:EK_PYTHON } else { 'python' }),
	[switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = Join-Path $repo 'target'

function Step ($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail ($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# --- Prerequisites -----------------------------------------------------------

$Jdk = (Resolve-Path $Jdk -ErrorAction SilentlyContinue).Path
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
	Fail "No JDK at '$Jdk'. Pass -Jdk or set EK_JDK to a Java 8 JDK."
}
$jre = Join-Path $Jdk 'jre'
if (-not (Test-Path (Join-Path $jre 'bin\java.exe'))) {
	Fail "The JDK at $Jdk has no jre\ folder to ship. Use a JDK 8 build that includes one (Temurin 8 does)."
}
$javaVersion = (& (Join-Path $Jdk 'bin\java.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "1\.8\.') {
	Fail "The JDK at $Jdk is not Java 8:`n$javaVersion"
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
	$local = Join-Path $repo '..\tools\apache-maven-3.9.9\bin\mvn.cmd'
	if (Test-Path $local) { $mvn = Get-Command (Resolve-Path $local).Path } else { Fail 'Maven (mvn) is not on PATH.' }
}

& $Python -c 'import UnityPy, TypeTreeGeneratorAPI, PyInstaller' 2>$null
if ($LASTEXITCODE -ne 0) {
	Fail "Python '$Python' lacks the extractor's packages: $Python -m pip install -r tools\gamedata\requirements.txt"
}

$version = ([xml](Get-Content (Join-Path $repo 'pom.xml') -Raw)).project.version
Step "Eternity Keeper $version, JDK $Jdk"

# --- The editor and its launcher -----------------------------------------------

Step 'Building the jar and Eternity Keeper.exe'
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"
# clean: with a fixed jar name, shade would otherwise re-read the last build's
# already-shaded jar as its input.
$mavenArgs = @('-B', '-Pwin64', 'clean', 'install')
if ($SkipTests) { $mavenArgs += '-DskipTests' }
Push-Location $repo
try {
	& $mvn.Source @mavenArgs
	if ($LASTEXITCODE -ne 0) { Fail 'The Maven build failed.' }
} finally {
	Pop-Location
}

# --- The game-data reader ------------------------------------------------------

Step 'Freezing the game-data reader'
$frozen = Join-Path $target 'gamedata'
& $Python -m PyInstaller --noconfirm --clean --onedir --console --name extract_gamedata `
	--paths (Join-Path $repo 'tools\gamedata') `
	--hidden-import items --hidden-import stronghold --hidden-import identity `
	--collect-all UnityPy --collect-all TypeTreeGeneratorAPI `
	--collect-all texture2ddecoder --collect-all etcpak --collect-all astc_encoder `
	--collect-all archspec `
	--paths (Join-Path $PSScriptRoot 'stubs') --hidden-import fmod_toolkit `
	--distpath $frozen --workpath (Join-Path $target 'gamedata-build') `
	--specpath (Join-Path $target 'gamedata-build') `
	(Join-Path $repo 'tools\gamedata\extract_gamedata.py')
if ($LASTEXITCODE -ne 0) { Fail 'PyInstaller failed.' }

# FMOD is proprietary and must not ship. UnityPy imports fmod_toolkit whenever
# it exports anything, icons included, and the real one loads FMOD's DLL on
# import -- so stubs\fmod_toolkit stands in for it (the reader never touches
# audio). Leaving the module out altogether broke every icon.
if (Get-ChildItem $frozen -Recurse -Include 'fmod*.dll', 'libfmod*' -ErrorAction SilentlyContinue) {
	Fail 'FMOD found in the frozen reader; it must not be redistributed.'
}

# Icons are where a frozen build breaks: texture decoding pulls in native
# decoders, archspec's CPU tables and (via UnityPy.export) fmod_toolkit, none of
# which PyInstaller finds on its own. Each of those once left the reader
# writing every catalog and not one icon. The full run against a real install
# is tools\ui-tests\gamedata_ui.py with EK_RELEASE set; it takes minutes.
$selfTest = & (Join-Path $frozen 'extract_gamedata\extract_gamedata.exe') --self-test 2>&1
if ($LASTEXITCODE -ne 0) {
	Fail "The frozen reader cannot decode textures:`n$($selfTest | Out-String)"
}

# A folder that is not the game has to be refused, in the protocol the editor reads.
$probe = & (Join-Path $frozen 'extract_gamedata\extract_gamedata.exe') --game (Join-Path $target 'no-such-game') --out (Join-Path $target 'no-such-output')
if ($LASTEXITCODE -ne 2 -or "$probe" -notmatch '^ERROR ') {
	Fail "The frozen reader did not answer as expected: $probe"
}

# --- Staging -----------------------------------------------------------------

$release = Join-Path $target 'release'
$stage = Join-Path $release 'Eternity Keeper'
Step "Staging $stage"
if (Test-Path $release) { Remove-Item $release -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null

Copy-Item (Join-Path $target 'Eternity Keeper.exe') $stage
Copy-Item (Join-Path $target 'eternity-keeper.jar') $stage
Copy-Item (Join-Path $repo 'src\ui') (Join-Path $stage 'ui') -Recurse
New-Item -ItemType Directory -Force (Join-Path $stage 'lib\native') | Out-Null
Copy-Item (Join-Path $repo 'lib\native\win64') (Join-Path $stage 'lib\native\win64') -Recurse
Copy-Item $jre (Join-Path $stage 'jre') -Recurse
Copy-Item (Join-Path $frozen 'extract_gamedata') (Join-Path $stage 'gamedata') -Recurse
Copy-Item (Join-Path $PSScriptRoot 'README.txt') $stage
foreach ($doc in 'LICENSE', 'CHANGELOG.md', 'THIRD-PARTY-NOTICES.md') {
	Copy-Item (Join-Path $repo $doc) $stage
}

# --- Zip -----------------------------------------------------------------------

$zip = Join-Path $release "EternityKeeper-$version-win64.zip"
Step "Zipping $zip"
Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal

$size = '{0:N0} MB' -f ((Get-Item $zip).Length / 1MB)
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
Step "Done: $zip ($size)"
Write-Host "SHA-256 $hash"
Set-Content -Path "$zip.sha256" -Value "$hash  $(Split-Path $zip -Leaf)" -Encoding ascii
