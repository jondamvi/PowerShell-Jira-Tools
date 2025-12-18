
function Invoke-WebRequestAdvanced {
    param(
        [string]$Uri,
        [string]$OutFile = "response.html",
        [string]$CookieFile = "cookies.json",
        [string[]]$Headers = @(),
        [switch]$VerboseOutput,
        [switch]$SaveCookies
    )
    # Load cookies from file
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    if (Test-Path $CookieFile) {
        $cookies = Get-Content $CookieFile | ConvertFrom-Json
        foreach ($c in $cookies) {
            $session.Cookies.Add((New-Object System.Net.Cookie($c.Name, $c.Value, $c.Path, $c.Domain)))
        }
    }
    # Splat parameters
    $params = @{
        Uri = $Uri
        WebSession = $session
        OutFile = $OutFile
        PassThru = $true
        Verbose = $VerboseOutput
    }
    if ($Headers) { $params.Headers = $Headers }
    # Execute
    $response = Invoke-WebRequest @params
    # Save cookies if requested
    if ($SaveCookies) {
        $session.Cookies.GetCookies($Uri) | ConvertTo-Json | Set-Content $CookieFile
    }
    # Output headers to file
    $response.Headers | ConvertTo-Json | Set-Content "${OutFile%.html}_headers.json"
    return $response
}

# Basic with all features
Invoke-WebRequestAdvanced -Uri "https://example.com/api" -OutFile "output.html" -VerboseOutput -Headers @{"User-Agent"="PowerShell"}

# With cookies + save new cookies
Invoke-WebRequestAdvanced "https://site.com/profile" -CookieFile "mysession.json" -SaveCookies -VerboseOutput

# Headers + verbose + separate files
$headers = @{"Authorization"="Bearer token"; "Accept"="application/json"}
Invoke-WebRequestAdvanced "https://api.com/data" -Headers $headers -OutFile "data.json" -VerboseOutput









