$ErrorActionPreference = "Stop"

# Ensure working directory is the project root
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ((Split-Path -Leaf $scriptDir) -eq "scratch") {
    Set-Location (Split-Path -Parent $scriptDir)
} elseif ($scriptDir) {
    Set-Location $scriptDir
}

# Ensure target directories exist
$targetClasses = "target\classes"
New-Item -ItemType Directory -Force -Path $targetClasses | Out-Null

# Resolve protoc executable
$protocCandidates = @(
    "C:\Users\R c\.m2\repository\com\google\protobuf\protoc\3.10.0\protoc-3.10.0-windows-x86_64.exe",
    "target\protoc-plugins\protoc-3.10.0-windows-x86_64.exe",
    "$env:USERPROFILE\.m2\repository\com\google\protobuf\protoc\3.10.0\protoc-3.10.0-windows-x86_64.exe"
)
$protoc = $protocCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $protoc) {
    $found = Get-ChildItem -Path "$env:USERPROFILE\.m2\repository" -Filter "*protoc*.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $protoc = $found.FullName }
}

$protoFile = "src\main\proto\wa.proto"
$waJavaFile = "src\main\java\id\ja_wa\proto\Wa.java"

# Auto-generate Wa.java into src/main/java if missing or if wa.proto is newer
$needProtoc = (-not (Test-Path $waJavaFile))
if (-not $needProtoc) {
    $protoTime = (Get-Item $protoFile).LastWriteTime
    $waTime = (Get-Item $waJavaFile).LastWriteTime
    if ($protoTime -gt $waTime) {
        $needProtoc = $true
    }
}

if ($needProtoc) {
    if (-not $protoc) {
        Write-Error "protoc executable not found in .m2 repository!"
        exit 1
    }
    Write-Host "Generating Wa.java from $protoFile using protoc..."
    & $protoc -I=src\main\proto --java_out=src\main\java $protoFile
    if ($LASTEXITCODE -ne 0) {
        Write-Error "protoc failed with exit code $LASTEXITCODE"
        exit 1
    }
}

# Resolve classpath dependencies from .m2
$jars = (Get-ChildItem -Path "$env:USERPROFILE\.m2\repository" -Filter "*.jar" -Recurse | ForEach-Object { $_.FullName }) -join ";"
$cp = "$targetClasses;" + $jars

$javac = "C:\Program Files\Java\jdk-21\bin\javac.exe"
if (-not (Test-Path $javac)) {
    $javac = "javac"
}

# Compile all source files (including Wa.java in src/main/java)
$files = Get-ChildItem -Path "src\main\java", "examples" -Filter "*.java" -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[System.IO.File]::WriteAllLines("target\sources.txt", $files)

Write-Host "Compiling $($files.Count) Java source files..."
& $javac -cp $cp -d $targetClasses -J-Xmx1024m -proc:none "@target\sources.txt"

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCESS: All files compiled cleanly with exit code 0."
} else {
    Write-Host "FAILURE: javac exited with code $LASTEXITCODE"
}

