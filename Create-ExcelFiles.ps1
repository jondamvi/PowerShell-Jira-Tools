
$ErrorActionPreference = "Stop"

# 1. Prompt for input CSV file
do {
    $csvPath = Read-Host "Provide path to Input CSV file containing list of file names:`n(Press Enter to confirm file path, CTRL+C to abort)"
    if ([string]::IsNullOrWhiteSpace($csvPath)) {
        Write-Host "No file path provided. Exiting." -ForegroundColor Red
        exit
    }
    $csvPath = $csvPath.Trim('"').Trim()
} while (!(Test-Path -LiteralPath $csvPath -PathType Leaf))


# 2. Prompt for column name
do {
    $colName = Read-Host "Provide CSV Column Name containing list of file names:`n(Press Enter to confirm column name, CTRL+C to abort)"
    if ([string]::IsNullOrWhiteSpace($colName)) {
        Write-Host "No column name provided. Exiting." -ForegroundColor Red
        exit
    }
    $colName = $colName.Trim()
} while ($colName.Length -eq 0)


# 3. Import CSV and validate column
$csv = Import-Csv -LiteralPath $csvPath
$allProps = $csv | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name -Unique

if ($allProps -notcontains $colName) {
    Write-Host "ERROR: Column '$colName' not found in CSV. Exiting." -ForegroundColor Red
    exit
}

$values = $csv.$colName | ForEach-Object { if ($_ -is [string]) { $_.Trim() } else { $_ } } | Where-Object { $_ -ne $null }

if ($values.Count -eq 0) {
    Write-Host "ERROR: No data under specified column '$colName'. Exiting." -ForegroundColor Red
    exit
}

# 4. Check and warn duplicates
$duplicates = $values | Group-Object | Where-Object Count -GT 1
if ($duplicates) {
    $dupNames = $duplicates.Name -join ", "
    Write-Warning "Duplicate entries found in column '$colName': $dupNames"
    do {
        $resp = Read-Host "Continue anyway? (Y/N): (Press Enter to confirm, CTRL+C to abort)"
        if ([string]::IsNullOrWhiteSpace($resp)) { $resp = "N" }
        $resp = $resp.Trim().ToUpper()
    } while ($resp -notin @("Y", "N"))
    if ($resp -ne "Y") {
        Write-Host "User chose to abort. Exiting." -ForegroundColor Yellow
        exit
    }
}


# 5. Ask about template
do {
    $templateChoice = Read-Host "Would you like to use existing Template Excel file or create new blank excel files?`nWrite `"Y`" for Template or `"N`" for new file:`n(Press Enter to confirm, CTRL+C to abort)"
    if ([string]::IsNullOrWhiteSpace($templateChoice)) { $templateChoice = "N" }
    $templateChoice = $templateChoice.Trim().ToUpper()
} while ($templateChoice -notin @("Y", "N"))

$useTemplate = $templateChoice -eq "Y"
$templatePath = $null
if ($useTemplate) {
    do {
        $templatePath = Read-Host "Enter full path to Template Excel file (.xlsx):`n(Press Enter to confirm, CTRL+C to abort)"
        if ([string]::IsNullOrWhiteSpace($templatePath)) { $templatePath = $null }
        $templatePath = $templatePath.Trim('"').Trim()
    } while ($templatePath -and !(Test-Path -LiteralPath $templatePath -PathType Leaf))

    if (-not $templatePath) {
        Write-Host "No template path provided. Exiting." -ForegroundColor Red
        exit
    }
}


# 6. Create output directory on Desktop
$dtStr = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$baseDir = Join-Path $env:USERPROFILE "Desktop"
$outDirName = "GeneratedExcelFiles_$dtStr"
$outDir = Join-Path $baseDir $outDirName
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

Write-Host "Output directory created: $outDir" -ForegroundColor Green


# 7. Generate files
foreach ($name in $values) {
    $fileName = "$name.xlsx"
    $dstPath = Join-Path $outDir $fileName

    if ($useTemplate) {
        Copy-Item -LiteralPath $templatePath -Destination $dstPath -Force
    } else {
        # Create minimal blank xlsx structure (empty file, Excel will repair on open if needed)
        $excel = New-Object -ComObject Excel.Application
        $excel.Visible = $false
        $excel.DisplayAlerts = $false
        $wb = $excel.Workbooks.Add()
        $wb.SaveAs($dstPath, 51)  # 51 = xlOpenXMLWorkbook (.xlsx)
        $wb.Close()
        $excel.Quit()
        [System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null
        Remove-Variable excel, wb, $dstPath
    }

    Write-Host "Created: $dstPath" -ForegroundColor Cyan
}

Write-Host "Done." -ForegroundColor Green
