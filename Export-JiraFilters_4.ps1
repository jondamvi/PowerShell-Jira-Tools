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
    
    $args = @(
        "-s", "-k"
        "--cookie", $cookieFile
        "--cookie-jar", $cookieFile
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
    
    $result = & $curlExe $args | Out-String
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
    }
}
function Get-FilterJQL {
    param([string]$FilterId)
    
    $filterUrl = "$jiraBaseUrl/issues/?filter=$FilterId"
    Write-Status "  Fetching JQL for filter $FilterId..." -Color Gray
    
    # Initialize result object
    $result = [PSCustomObject]@{
        FilterURL = $filterUrl
        FilterJQL = $null
        StringJQL = ""
        Errors = [PSCustomObject]@{
            ParseHTML = ""
            ConvertJSON = ""
            LoadHTML = ""
        }
        Status = "Failed"
    }
    
    # Try to fetch the filter page
    try {
        $html = Invoke-CurlRequest -Url $filterUrl
        
        if ([string]::IsNullOrWhiteSpace($html) -or $html.Length -lt 100) {
            $result.Errors.LoadHTML = "Empty or invalid HTML response (length: $($html.Length))"
            return $result
        }
    }
    catch {
        $result.Errors.LoadHTML = $_.Exception.Message
        return $result
    }
    
    # Parse the data-issue-table-model-state attribute from the div
    try {
        # Look for: <div class="navigator-content" data-issue-table-model-state="VALUE">
        $pattern = '<div[^>]*class="[^"]*navigator-content[^"]*"[^>]*data-issue-table-model-state="([^"]*)"'
        
        if ($html -match $pattern) {
            $rawJqlString = $matches[1]
            
            # Replace HTML-encoded quotes
            $jsonString = $rawJqlString -replace '&quot;', '"'
            
            # Also handle other common HTML entities
            $jsonString = $jsonString -replace '&amp;', '&'
            $jsonString = $jsonString -replace '&lt;', '<'
            $jsonString = $jsonString -replace '&gt;', '>'
            $jsonString = $jsonString -replace '&apos;', "'"
            
            $result.StringJQL = $jsonString
            
            # Try to convert from JSON
            try {
                $jqlObject = $jsonString | ConvertFrom-Json
                $result.FilterJQL = $jqlObject
                $result.Status = "Success"
                
                Write-Status "    ✓ JQL extracted and parsed from data-issue-table-model-state" -Color Green
            }
            catch {
                $result.Errors.ConvertJSON = $_.Exception.Message
                $result.Status = "Failed"
                Write-Status "    ⚠ JQL extracted but JSON conversion failed: $($_.Exception.Message)" -Color Yellow
            }
        }
        else {
            # Try alternative: look for textarea with id="advanced-search"
            $textareaPattern = '<textarea[^>]*id=["\']?advanced-search["\']?[^>]*>(.*?)</textarea>'
            $textareaMatch = [regex]::Match($html, $textareaPattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
            
            if ($textareaMatch.Success) {
                $jqlText = $textareaMatch.Groups[1].Value
                $jqlText = [System.Web.HttpUtility]::HtmlDecode($jqlText)
                $jqlText = $jqlText.Trim()
                
                $result.StringJQL = $jqlText
                # For textarea, the JQL is plain text, not JSON
                $result.FilterJQL = [PSCustomObject]@{
                    jql = $jqlText
                }
                $result.Status = "Success"
                
                Write-Status "    ✓ JQL extracted from textarea" -Color Green
            }
            else {
                $result.Errors.ParseHTML = "Could not find data-issue-table-model-state attribute or advanced-search textarea in HTML"
                Write-Status "    ✗ Could not find JQL in HTML" -Color Red
            }
        }
    }
    catch {
        $result.Errors.ParseHTML = $_.Exception.Message
        return $result
    }
    
    return $result
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

# Step 1a: GET login page to obtain XSRF token
Write-Status "  Getting login page to obtain XSRF token..." -Color Gray
$loginGetArgs = @(
    "-s", "-k"
    "--cookie-jar", $cookieFile
    $jiraBaseUrl + "/login.jsp"
)
$loginPageResponse = & $curlExe $loginGetArgs

# Check if cookie file was created
if (Test-Path $cookieFile) {
    Write-Status "  ✓ Cookie file created with XSRF token" -Color Green
} else {
    Write-Status "  ✗ Failed to create cookie file" -Color Red
    exit 1
}

# Step 1b: POST login with credentials
Write-Status "  Submitting login credentials..." -Color Gray
$loginPostArgs = @(
    "-s", "-k", "-L"
    "--cookie", $cookieFile
    "--cookie-jar", $cookieFile
    "-H", "Content-Type: application/x-www-form-urlencoded"
    "-H", "Referer: $jiraBaseUrl/login.jsp"
    "-d", "username=$username&password=$password"
    $jiraBaseUrl + "/login.jsp"
)
$loginResponse = & $curlExe $loginPostArgs | Out-String
if ($loginResponse -match "System Dashboard" -or $loginResponse -match "Dashboard") {
    Write-Status "✓ Login successful" -Color Green
} else {
    Write-Status "✗ Login failed - check credentials" -Color Red
    if ($loginResponse.Length -gt 0) {
        Write-Status "Response preview: $($loginResponse.Substring(0, [Math]::Min(200, $loginResponse.Length)))" -Color Gray
    }
    exit 1
}

# Verify cookie file
if (Test-Path $cookieFile) {
    $cookieSize = (Get-Item $cookieFile).Length
    Write-Status "✓ Cookie file: $cookieFile ($cookieSize bytes)" -Color Green
} else {
    Write-Status "✗ Cookie file was not created!" -Color Red
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
# Step 3: Load filter management page to establish session state
Write-Status ""
Write-Status "Step 3: Loading filter management page..." -Color Yellow

if ($useAdminMode) {
    $filterPageUrl = "$jiraBaseUrl/secure/admin/filters/ViewSharedFilters.jspa"
} else {
    $filterPageUrl = "$jiraBaseUrl/secure/ManageFilters.jspa"
}

$filterPageArgs = @(
    "-s", "-k"
    "--cookie", $cookieFile
    "--cookie-jar", $cookieFile
    $filterPageUrl
)
$filterPageResponse = & $curlExe $filterPageArgs | Out-String

if ($filterPageResponse -match "ManageFilters" -or $filterPageResponse -match "filter") {
    Write-Status "✓ Filter page loaded successfully" -Color Green
} else {
    Write-Status "⚠ Filter page response unclear - continuing anyway..." -Color Yellow
}

# Step 4: Paginate through filter results
Write-Status ""
Write-Status "Step 4: Fetching shared filters via pagination..." -Color Yellow
$pageOffset = 0
$pageNumber = 1
$hasMorePages = $true
while ($hasMorePages) {
    Write-Status "  Fetching page $pageNumber (offset: $pageOffset)..." -Color Gray
    
    # Build pagination URL - simple direct approach
    if ($useAdminMode) {
        $pageUrl = "$jiraBaseUrl/secure/admin/filters/ViewSharedFilters.jspa?filterView=search&searchName=&searchOwnerUserName=&searchShareType=any&pagingOffset=$pageOffset&sortAscending=true&sortColumn=name"
    } else {
        $pageUrl = "$jiraBaseUrl/secure/ManageFilters.jspa?filterView=search&searchName=&searchOwnerUserName=&searchShareType=any&pagingOffset=$pageOffset&sortAscending=true&sortColumn=name"
    }
    
    Write-Status "  URL: $pageUrl" -Color Gray
    
    $pageArgs = @(
        "-s", "-k"
        "--cookie", $cookieFile
        "--cookie-jar", $cookieFile
        $pageUrl
    )
    $html = & $curlExe $pageArgs | Out-String
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
# Step 5: Fetch JQL for each filter
Write-Status ""
Write-Status "Step 5: Fetching JQL queries for filters..." -Color Yellow
$processedCount = 0
foreach ($filter in $allFilters) {
    $processedCount++
    Write-Status "  [$processedCount/$($allFilters.Count)] Processing: $($filter.FilterName)" -Color Gray
    
    $jqlResult = Get-FilterJQL -FilterId $filter.FilterId
    
    # Add JQL result to filter object
    Add-Member -InputObject $filter -NotePropertyName "JQLResult" -NotePropertyValue $jqlResult -Force
    
    if ($jqlResult.Status -eq "Success") {
        Write-Status "    ✓ JQL fetched successfully" -Color Green
    } else {
        Write-Status "    ⚠ JQL fetch had issues" -Color Yellow
        if ($jqlResult.Errors.LoadHTML) {
            Write-Status "      LoadHTML: $($jqlResult.Errors.LoadHTML)" -Color Red
        }
        if ($jqlResult.Errors.ParseHTML) {
            Write-Status "      ParseHTML: $($jqlResult.Errors.ParseHTML)" -Color Red
        }
        if ($jqlResult.Errors.ConvertJSON) {
            Write-Status "      ConvertJSON: $($jqlResult.Errors.ConvertJSON)" -Color Red
        }
    }
    
    Start-Sleep -Milliseconds 300
}
# Step 6: Save to global variable
Write-Status ""
Write-Status "Step 6: Saving results to global variable..." -Color Yellow
$Global:JiraOutput = $allFilters
Write-Status "✓ Results saved to `$Global:JiraOutput" -Color Green
Write-Status "  Access the data with: `$Global:JiraOutput" -Color Cyan

# Step 7: Export to JSON
Write-Status ""
Write-Status "Step 7: Exporting to JSON..." -Color Yellow
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
