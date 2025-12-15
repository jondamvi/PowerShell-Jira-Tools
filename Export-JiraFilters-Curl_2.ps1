# JIRA Shared Filters Scraper using curl
# Scrapes shared filters from JIRA web interface and exports to JSON
# ============================================================
# CONFIGURATION
# ============================================================
$jiraBaseUrl = "https://testjira.testcompany.org"
$username = "your-username"
$password = "your-password"
$outputFile = "jira-shared-filters.json"
$useAdminMode = $false  # Set to $true to use admin mode
$adminPassword = "your-admin-password"  # Only needed if useAdminMode = $true
$debugMode = $false  # Set to $true to save HTML responses for debugging
# ============================================================
# SCRIPT VARIABLES
# ============================================================
$cookieFile = "$env:TEMP\jira-cookies.txt"
$curlExe = "curl.exe"
$allFilters = @()
# ============================================================
# HELPER FUNCTIONS
# ============================================================
function Write-Status {
    param([string]$Message, [string]$Color = "White")
    Write-Host $Message -ForegroundColor $Color
}
function Invoke-CurlRequest {
    param(
        [string]$Url,
        [string]$Method = "GET",
        [string]$Data = "",
        [switch]$FollowRedirects,
        [switch]$IncludeHeaders
    )
    
    # Check if cookie file exists before trying to use it
    if (-not (Test-Path $cookieFile)) {
        Write-Status "  WARNING: Cookie file doesn't exist yet: $cookieFile" -Color Yellow
    } else {
        Write-Status "  Using cookie file: $cookieFile ($(Get-Item $cookieFile | Select-Object -ExpandProperty Length) bytes)" -Color Gray
    }
    
    $args = @(
        "-s"  # Silent mode
        "-k"  # Insecure - ignore SSL errors
        "-b", $cookieFile  # Use cookies FROM this file
        "-c", $cookieFile  # Save cookies TO this file
        "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )
    if ($FollowRedirects) {
        $args += "-L"
    }
    if ($IncludeHeaders) {
        $args += "-i"
    }
    if ($Method -ne "GET") {
        $args += "-X", $Method
    }
    if ($Data) {
        $args += "-d", $Data
        $args += "-H", "Content-Type: application/x-www-form-urlencoded"
    }
    $args += $Url
    
    Write-Status "  Curl command: curl.exe $($args -join ' ')" -Color Gray
    
    $result = & $curlExe $args
    return $result
}
function Extract-HtmlAttribute {
    param([string]$Html, [string]$Pattern)
    if ($Html -match $Pattern) {
        return $matches[1]
    }
    return $null
}
function Parse-FilterRow {
    param([string]$RowHtml)
    # Extract filter ID from tr tag attributes
    if ($RowHtml -match 'data-filter-id="(\d+)"') {
        $filterId = $matches[1]
    } elseif ($RowHtml -match 'id="mf_(\d+)"') {
        $filterId = $matches[1]
    } else {
        return $null
    }
    # Extract filter name from link
    if ($RowHtml -match '<a[^>]*id="filterlink_\d+"[^>]*>([^<]+)</a>') {
        $filterName = $matches[1].Trim()
        $filterName = [System.Web.HttpUtility]::HtmlDecode($filterName)
    } else {
        return $null
    }
    # Extract owner name and email
    $ownerName = ""
    $ownerEmail = ""
    if ($RowHtml -match '<span[^>]*data-filter-field="owner-full-name"[^>]*>([^<]*)</span>\s*\(([^)]+)\)') {
        $ownerName = $matches[1].Trim()
        $ownerEmail = $matches[2].Trim()
    } elseif ($RowHtml -match '<span[^>]*data-filter-field="owner-full-name"[^>]*>([^<]*)</span>') {
        $ownerName = $matches[1].Trim()
    } elseif ($RowHtml -match '\(([^)]+@[^)]+)\)') {
        $ownerEmail = $matches[1].Trim()
    }
    # Extract shared with information
    $sharedGroups = @()
    $shareListMatch = [regex]::Match($RowHtml, '<ul[^>]*class="[^"]*shareList[^"]*"[^>]*>(.*?)</ul>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($shareListMatch.Success) {
        $shareListHtml = $shareListMatch.Groups[1].Value
        # Check if private
        if ($shareListHtml -match 'Private') {
            return $null
        }
        # Extract all <li> entries
        $liMatches = [regex]::Matches($shareListHtml, '<li[^>]*>(.*?)</li>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
        foreach ($liMatch in $liMatches) {
            $liHtml = $liMatch.Groups[1].Value
            # Extract title attribute for description
            $description = ""
            if ($liMatch.Value -match 'title="([^"]*)"') {
                $description = $matches[1]
                $description = [System.Web.HttpUtility]::HtmlDecode($description)
            }
            # Extract text content and parse group/access
            $liText = $liHtml -replace '<[^>]+>', ' '
            $liText = $liText -replace '\s+', ' '
            $liText = $liText.Trim()
            $liText = [System.Web.HttpUtility]::HtmlDecode($liText)
            # Try to extract group name and access
            $groupName = ""
            $access = ""
            if ($liText -match ':\s*(.+?)\s*\((\w+)\)\s*$') {
                $groupName = $matches[1].Trim()
                $access = $matches[2].Trim()
            } elseif ($liText -match '(.+?)\s*\((\w+)\)\s*$') {
                $groupName = $matches[1].Trim()
                $access = $matches[2].Trim()
            } else {
                $groupName = $liText
            }
            if ($groupName) {
                $sharedGroups += [PSCustomObject]@{
                    GroupName = $groupName
                    Access = $access
                    Description = $description
                }
            }
        }
    }
    # Skip if no shared groups found (private filter)
    if ($sharedGroups.Count -eq 0) {
        return $null
    }
    return [PSCustomObject]@{
        FilterId = $filterId
        FilterName = $filterName
        OwnerName = $ownerName
        OwnerEmail = $ownerEmail
        SharedGroups = $sharedGroups
        Popularity = 0
        JQL = ""
    }
}
function Get-FilterJQL {
    param([string]$FilterId)
    Write-Status "  Fetching JQL for filter $FilterId..." -Color Gray
    $filterUrl = "$jiraBaseUrl/issues/?filter=$FilterId"
    $html = Invoke-CurlRequest -Url $filterUrl
    # Look for textarea with id="advanced-search"
    $jqlMatch = [regex]::Match($html, '<textarea[^>]*id=["'']?advanced-search["'']?[^>]*>(.*?)</textarea>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($jqlMatch.Success) {
        $jql = $jqlMatch.Groups[1].Value
        # Decode HTML entities
        $jql = [System.Web.HttpUtility]::HtmlDecode($jql)
        $jql = $jql.Trim()
        return $jql
    }
    # Alternative: look for JQL in JavaScript variable or data attribute
    if ($html -match 'data-jql=["'']([^"'']*(?:\\.[^"'']*)*)["'']') {
        $jql = $matches[1]
        $jql = [System.Web.HttpUtility]::HtmlDecode($jql)
        return $jql.Trim()
    }
    return ""
}
# ============================================================
# MAIN SCRIPT
# ============================================================
Write-Status "========================================" -Color Cyan
Write-Status "JIRA Shared Filters Scraper" -Color Cyan
Write-Status "========================================" -Color Cyan
Write-Status ""
# Load System.Web for HTML decoding
Add-Type -AssemblyName System.Web
# Ensure temp directory exists and is writable
if (-not (Test-Path $env:TEMP)) {
    Write-Status "Creating temp directory..." -Color Yellow
    New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
}
# Clean up old cookie file
if (Test-Path $cookieFile) {
    Remove-Item $cookieFile -Force
}
# Step 1: Login
Write-Status "Step 1: Logging in to JIRA..." -Color Yellow
Write-Status "Cookie file will be saved to: $cookieFile" -Color Gray
$loginUrl = "$jiraBaseUrl/login.jsp"
$encodedUsername = [System.Web.HttpUtility]::UrlEncode($username)
$encodedPassword = [System.Web.HttpUtility]::UrlEncode($password)
$loginData = "username=$encodedUsername&password=$encodedPassword"
# Make login request with explicit curl call to ensure cookies are saved
$loginArgs = @(
    "-s", "-k"
    "-L"  # Follow redirects
    "-c", $cookieFile  # SAVE cookies to this file
    "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    "-d", $loginData
    "-H", "Content-Type: application/x-www-form-urlencoded"
    "-H", "Referer: $jiraBaseUrl/login.jsp"
    $loginUrl
)
Write-Status "Executing login with cookie save to: $cookieFile" -Color Gray
$loginResponse = & $curlExe $loginArgs
if ($loginResponse -match "System Dashboard" -or $loginResponse -match "Dashboard") {
    Write-Status "✓ Login successful" -Color Green
} else {
    Write-Status "✗ Login failed - check credentials" -Color Red
    Write-Status "Response preview: $($loginResponse.Substring(0, [Math]::Min(200, $loginResponse.Length)))" -Color Gray
    exit 1
}
# Debug: Check if cookies were saved
if (Test-Path $cookieFile) {
    $cookieContent = Get-Content $cookieFile -Raw
    Write-Status "✓ Cookie file created ($([Math]::Round((Get-Item $cookieFile).Length / 1, 0)) bytes)" -Color Green
    if ($cookieContent -match "JSESSIONID") {
        Write-Status "✓ Session cookie (JSESSIONID) found" -Color Green
    } elseif ($cookieContent -match "atlassian") {
        Write-Status "✓ Atlassian session cookie found" -Color Green
    } else {
        Write-Status "⚠ Warning: No recognizable session cookie found" -Color Yellow
        Write-Status "Cookie content preview:" -Color Gray
        Write-Status $cookieContent.Substring(0, [Math]::Min(300, $cookieContent.Length)) -Color Gray
    }
} else {
    Write-Status "✗ Cookie file was not created!" -Color Red
    exit 1
}
# Test session with a simple authenticated request
Write-Status "Testing session with authenticated request..." -Color Yellow
$testUrl = "$jiraBaseUrl/secure/Dashboard.jspa"
$testResponse = Invoke-CurlRequest -Url $testUrl
if ($testResponse -match "Dashboard" -and $testResponse -notmatch "login") {
    Write-Status "✓ Session is working - authenticated requests successful" -Color Green
} else {
    Write-Status "✗ Session test failed - cookies may not be working properly" -Color Red
    Write-Status "Test response preview: $($testResponse.Substring(0, [Math]::Min(300, $testResponse.Length)))" -Color Gray
    exit 1
}
# Step 2: Switch to admin mode if requested
if ($useAdminMode) {
    Write-Status ""
    Write-Status "Step 2: Switching to admin mode..." -Color Yellow
    $adminAuthUrl = "$jiraBaseUrl/secure/admin/WebSudoAuthenticate.jspa"
    $encodedAdminPassword = [System.Web.HttpUtility]::UrlEncode($adminPassword)
    $adminData = "webSudoPassword=$encodedAdminPassword&webSudoIsPost=false"
    $adminResponse = Invoke-CurlRequest -Url $adminAuthUrl -Method POST -Data $adminData -FollowRedirects
    if ($adminResponse -match "Administrator" -or $adminResponse -match "admin") {
        Write-Status "✓ Admin mode activated" -Color Green
    } else {
        Write-Status "⚠ Admin mode activation uncertain - continuing anyway" -Color Yellow
    }
    $filterBaseUrl = "$jiraBaseUrl/secure/admin/filters/ViewSharedFilters.jspa"
} else {
    $filterBaseUrl = "$jiraBaseUrl/secure/ManageFilters.jspa"
}
# Step 3: Fetch filters with pagination
Write-Status ""
Write-Status "Step 3: Fetching shared filters..." -Color Yellow

# First, submit the search form to get results
Write-Status "Submitting search form..." -Color Yellow
if ($useAdminMode) {
    $searchUrl = "$jiraBaseUrl/secure/admin/filters/ViewSharedFilters.jspa"
} else {
    $searchUrl = "$jiraBaseUrl/secure/ManageFilters.jspa"
}

# Submit search form with empty criteria to get all shared filters
$searchData = "filterView=search&Search=Search&searchName=&searchOwnerUserName=&searchShareType=any"
$searchResponse = Invoke-CurlRequest -Url $searchUrl -Method POST -Data $searchData -FollowRedirects

if ($searchResponse -match "mf_browse" -or $searchResponse -match "filter") {
    Write-Status "✓ Search form submitted successfully" -Color Green
} else {
    Write-Status "⚠ Search form response unclear - continuing anyway..." -Color Yellow
    if ($debugMode) {
        $debugFile = "jira-search-response-debug.html"
        $searchResponse | Out-File -FilePath $debugFile -Encoding UTF8
        Write-Status "  Saved search response to: $debugFile" -Color Gray
    }
}

# Now fetch paginated results
$pageOffset = 0
$pageNumber = 1
$hasMorePages = $true
while ($hasMorePages) {
    Write-Status "  Fetching page $pageNumber (offset: $pageOffset)..." -Color Gray
    if ($useAdminMode) {
        $pageUrl = "$jiraBaseUrl/secure/admin/filters/ViewSharedFilters.jspa?filterView=search&searchName=&searchOwnerUserName=&searchShareType=any&pagingOffset=$pageOffset&sortAscending=true&sortColumn=name"
    } else {
        $pageUrl = "$jiraBaseUrl/secure/ManageFilters.jspa?filterView=search&searchName=&searchOwnerUserName=&searchShareType=any&pagingOffset=$pageOffset&sortAscending=true&sortColumn=name"
    }
    
    Write-Status "  URL: $pageUrl" -Color Gray
    $html = Invoke-CurlRequest -Url $pageUrl
    # Debug: Save HTML if debug mode is enabled
    if ($debugMode) {
        $debugFile = "jira-filters-page$pageNumber-debug.html"
        $html | Out-File -FilePath $debugFile -Encoding UTF8
        Write-Status "  Debug: Saved HTML to $debugFile" -Color Gray
    }
    # Parse filter table rows - look for table with id="mf_browse"
    $tableMatch = [regex]::Match($html, '<table[^>]*id="mf_browse"[^>]*>(.*?)</table>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $tableMatch.Success) {
        Write-Status "  No filter table found on page $pageNumber" -Color Yellow
        
        # Save HTML for debugging
        $debugFile = "jira-filters-page$pageNumber-FAILED-$(Get-Date -Format 'yyyyMMdd-HHmmss').html"
        $html | Out-File -FilePath $debugFile -Encoding UTF8
        Write-Status "  ✓ Saved HTML output to: $debugFile" -Color Cyan
        
        # Debug: Check if we're actually logged in
        if ($html -match "login" -or $html -match "log in" -or $html -match "Log In") {
            Write-Status "  ERROR: Page contains login elements - session may have expired!" -Color Red
            Write-Status "  Check the saved HTML file: $debugFile" -Color Yellow
        } else {
            Write-Status "  Page loaded but no filter table with id='mf_browse' found" -Color Yellow
            Write-Status "  Page length: $($html.Length) characters" -Color Gray
            Write-Status "  Check the saved HTML file: $debugFile" -Color Yellow
            # Check if table exists with different structure
            if ($html -match '<table') {
                Write-Status "  Found other table tags on page - check HTML structure in debug file" -Color Gray
            }
        }
        break
    }
    $tableHtml = $tableMatch.Groups[1].Value
    # Extract rows with data-filter-id attribute
    $rowMatches = [regex]::Matches($tableHtml, '<tr[^>]*data-filter-id="\d+"[^>]*>.*?</tr>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $filtersOnPage = 0
    foreach ($rowMatch in $rowMatches) {
        $rowHtml = $rowMatch.Value
        # Parse filter row
        $filter = Parse-FilterRow -RowHtml $rowHtml
        if ($filter) {
            $filtersOnPage++
            Write-Status "    Found filter: $($filter.FilterName) (ID: $($filter.FilterId))" -Color Gray
            $allFilters += $filter
        }
    }
    Write-Status "  Page $pageNumber: Found $filtersOnPage shared filters" -Color Green
    # Check if there are more pages
    if ($filtersOnPage -eq 0 -or $html -notmatch 'pagingOffset=' + ($pageOffset + 20)) {
        $hasMorePages = $false
    } else {
        $pageOffset += 20
        $pageNumber++
        Start-Sleep -Milliseconds 500
    }
}
Write-Status ""
Write-Status "Total shared filters found: $($allFilters.Count)" -Color Green
# Step 4: Fetch JQL for each filter
Write-Status ""
Write-Status "Step 4: Fetching JQL queries for filters..." -Color Yellow
$processedCount = 0
foreach ($filter in $allFilters) {
    $processedCount++
    Write-Status "  [$processedCount/$($allFilters.Count)] Processing: $($filter.FilterName)" -Color Gray
    $jql = Get-FilterJQL -FilterId $filter.FilterId
    if ($jql) {
        $filter.JQL = $jql
        Write-Status "    ✓ JQL extracted ($($jql.Length) chars)" -Color Green
    } else {
        Write-Status "    ⚠ Could not extract JQL" -Color Yellow
    }
    Start-Sleep -Milliseconds 300
}
# Step 5: Export to JSON
Write-Status ""
Write-Status "Step 5: Exporting to JSON..." -Color Yellow
try {
    # Convert to JSON with proper UTF-8 encoding
    $json = $allFilters | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($outputFile, $json, [System.Text.Encoding]::UTF8)
    Write-Status "✓ Export completed successfully!" -Color Green
    Write-Status "  Output file: $outputFile" -Color Cyan
    Write-Status "  Total filters exported: $($allFilters.Count)" -Color White
    if (Test-Path $outputFile) {
        $fileSize = [Math]::Round((Get-Item $outputFile).Length / 1KB, 2)
        Write-Status "  File size: $fileSize KB" -Color Gray
    }
} catch {
    Write-Status "✗ Export failed: $($_.Exception.Message)" -Color Red
    exit 1
}
# Cleanup
if (Test-Path $cookieFile) {
    Remove-Item $cookieFile -Force
}
Write-Status ""
Write-Status "========================================" -Color Cyan
Write-Status "Script completed successfully!" -Color Green
Write-Status "========================================" -Color Cyan
