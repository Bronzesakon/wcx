$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$classesDir = Join-Path $repoRoot '.audit/deadline-check-classes'
$classesDir = [IO.Path]::GetFullPath($classesDir)
if (-not $classesDir.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Regression output must stay inside the repository'
}
$production = Join-Path $repoRoot 'app/src/main/java/com/Johnny/wcx/features/items/chat/ScheduledMessageDeadline.java'
$checks = Join-Path $PSScriptRoot 'ScheduledMessageDeadlineCheck.java'
try {
    New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
    & javac --release 17 -d $classesDir $production $checks
    if ($LASTEXITCODE -ne 0) { throw 'Deadline regression check did not compile' }
    & java -cp $classesDir com.Johnny.wcx.features.items.chat.ScheduledMessageDeadlineCheck
    if ($LASTEXITCODE -ne 0) { throw 'Deadline regression check failed' }
} finally {
    if (Test-Path -LiteralPath $classesDir) { Remove-Item -LiteralPath $classesDir -Recurse -Force }
}
