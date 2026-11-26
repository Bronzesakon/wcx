$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$classesDir = Join-Path $repoRoot '.audit/deadline-check-classes'
$classesDir = [IO.Path]::GetFullPath($classesDir)
if (-not $classesDir.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Regression output must stay inside the repository'
}
$productionDeadline = Join-Path $repoRoot 'app/src/main/java/com/Johnny/wcx/features/items/chat/ScheduledMessageDeadline.java'
$productionProgress = Join-Path $repoRoot 'app/src/main/java/com/Johnny/wcx/features/items/chat/ScheduledMessageProgress.java'
$productionPlan = Join-Path $repoRoot 'app/src/main/java/com/Johnny/wcx/features/items/chat/ScheduledMessagePlan.java'
$productionTiming = Join-Path $repoRoot 'app/src/main/java/com/Johnny/wcx/features/items/chat/ScheduledMessageTimingStats.java'
$deadlineChecks = Join-Path $PSScriptRoot 'ScheduledMessageDeadlineCheck.java'
$progressChecks = Join-Path $PSScriptRoot 'ScheduledMessageProgressCheck.java'
$planChecks = Join-Path $PSScriptRoot 'ScheduledMessagePlanCheck.java'
$timingChecks = Join-Path $PSScriptRoot 'ScheduledMessageTimingStatsCheck.java'
try {
    New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
    & javac --release 17 -d $classesDir $productionDeadline $productionProgress $productionPlan $productionTiming $deadlineChecks $progressChecks $planChecks $timingChecks
    if ($LASTEXITCODE -ne 0) { throw 'Scheduled-message regression checks did not compile' }
    & java -cp $classesDir com.Johnny.wcx.features.items.chat.ScheduledMessageDeadlineCheck
    if ($LASTEXITCODE -ne 0) { throw 'Deadline regression check failed' }
    & java -cp $classesDir com.Johnny.wcx.features.items.chat.ScheduledMessageProgressCheck
    if ($LASTEXITCODE -ne 0) { throw 'Progress regression check failed' }
    & java -cp $classesDir com.Johnny.wcx.features.items.chat.ScheduledMessagePlanCheck
    if ($LASTEXITCODE -ne 0) { throw 'Plan regression check failed' }
    & java -cp $classesDir com.Johnny.wcx.features.items.chat.ScheduledMessageTimingStatsCheck
    if ($LASTEXITCODE -ne 0) { throw 'Timing-stat regression check failed' }
} finally {
    if (Test-Path -LiteralPath $classesDir) { Remove-Item -LiteralPath $classesDir -Recurse -Force }
}
