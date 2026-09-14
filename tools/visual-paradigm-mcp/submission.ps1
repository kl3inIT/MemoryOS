param(
    [string]$TemplatePath = "D:\Workspaces\SupportPE\SWD392\TEST\SWD392PE4SP262585741given\Q-1\SWD392_PE_SP26_ID.docx",
    [string]$OutputPath = "D:\Workspaces\SupportPE\SWD392\TEST\SWD392PE4SP262585741given\Q-1\SWD392_PE_SP26_ID_COMPLETED.docx",
    [string]$BridgeUrl = "http://127.0.0.1:2026",
    [string]$ClassDiagramName = "Q1 EMS Design Class Diagram - Gate 2",
    [string]$SequenceDiagramName = "Q2 Register for Event RESTORED",
    [string]$StateDiagramName = "Q3 Event Lifecycle State Machine FINAL",
    [string]$ClassImagePath = "",
    [string]$SequenceImagePath = "",
    [string]$StateImagePath = "",
    [string]$ExamCode = "SWD392_PE_SP26",
    [string]$DateTimeText = (Get-Date -Format "yyyy-MM-dd HH:mm"),
    [switch]$WithoutImages,
    [switch]$UseNativeExport,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$wdAlignParagraphLeft = 0
$wdAlignParagraphCenter = 1
$wdCollapseStart = 1
$wdPageBreak = 7
$wdDoNotSaveChanges = 0
$msoTrue = -1

function Invoke-VpTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ToolName,
        [Parameter(Mandatory = $true)]
        [hashtable]$Arguments
    )

    $body = @{
        toolName = $ToolName
        arguments = $Arguments
    } | ConvertTo-Json -Depth 12

    $response = Invoke-RestMethod `
        -Uri "$BridgeUrl/execute" `
        -Method Post `
        -ContentType "application/json" `
        -Body $body `
        -TimeoutSec 60

    if (-not $response.result) {
        throw "Visual Paradigm tool '$ToolName' returned no result."
    }

    Write-Host $response.result
}

function Find-ParagraphIndex {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [string]$ExactText,
        [int]$Occurrence = 1
    )

    $normalizedExpected = ($ExactText -replace "\s+", " ").Trim()
    $seen = 0
    for ($index = 1; $index -le $Document.Paragraphs.Count; $index++) {
        $text = $Document.Paragraphs.Item($index).Range.Text
        $normalizedText = ($text -replace "\s+", " ").Trim([char]13, [char]7, " ", "`t")
        if ($normalizedText -eq $normalizedExpected) {
            $seen++
            if ($seen -eq $Occurrence) {
                return $index
            }
        }
    }

    throw "Could not find paragraph occurrence $Occurrence with text: $ExactText"
}

function Insert-PageBreakBefore {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [string]$Marker
    )

    $index = Find-ParagraphIndex -Document $Document -ExactText $Marker
    $range = $Document.Paragraphs.Item($index).Range.Duplicate
    $range.Collapse($wdCollapseStart)
    $range.InsertBreak($wdPageBreak)
}

function Get-BlankParagraphRangeAfter {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [string]$Marker,
        [int]$Occurrence = 1
    )

    $index = Find-ParagraphIndex -Document $Document -ExactText $Marker -Occurrence $Occurrence
    if ($index -ge $Document.Paragraphs.Count) {
        throw "No paragraph is available after marker: $Marker"
    }

    $range = $Document.Paragraphs.Item($index + 1).Range.Duplicate
    $range.End = $range.End - 1
    $range.Text = ""
    $range.Collapse($wdCollapseStart)
    return $range
}

function Insert-DiagramImage {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [string]$Marker,
        [Parameter(Mandatory = $true)]
        [string]$ImagePath,
        [Parameter(Mandatory = $true)]
        [string]$AlternativeText,
        [double]$MaxWidthPoints,
        [double]$MaxHeightPoints
    )

    $range = Get-BlankParagraphRangeAfter -Document $Document -Marker $Marker
    $image = $Document.InlineShapes.AddPicture($ImagePath, $false, $true, $range)
    $image.LockAspectRatio = $msoTrue

    $scale = [Math]::Min(
        $MaxWidthPoints / [double]$image.Width,
        $MaxHeightPoints / [double]$image.Height
    )
    if ($scale -ne 1.0) {
        $image.Width = [single]([double]$image.Width * $scale)
    }

    $image.AlternativeText = $AlternativeText
    $image.Range.ParagraphFormat.Alignment = $wdAlignParagraphCenter
    $image.Range.ParagraphFormat.LeftIndent = 0
    $image.Range.ParagraphFormat.RightIndent = 0
    $image.Range.ParagraphFormat.SpaceBefore = 6
    $image.Range.ParagraphFormat.SpaceAfter = 8

    try {
        $image.Range.ListFormat.RemoveNumbers()
    } catch {
        Write-Verbose "The image paragraph did not contain list formatting."
    }
}

function Insert-Explanation {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [int]$Occurrence,
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $range = Get-BlankParagraphRangeAfter `
        -Document $Document `
        -Marker "Brief Explanation:" `
        -Occurrence $Occurrence

    $range.Text = $Text
    $paragraph = $range.Paragraphs.Item(1)
    $paragraph.Range.Font.Name = "Times New Roman"
    $paragraph.Range.Font.Size = 10
    $paragraph.Range.Font.Bold = 0
    $paragraph.Range.ParagraphFormat.Alignment = $wdAlignParagraphLeft
    $paragraph.Range.ParagraphFormat.LeftIndent = 0
    $paragraph.Range.ParagraphFormat.RightIndent = 0
    $paragraph.Range.ParagraphFormat.SpaceBefore = 3
    $paragraph.Range.ParagraphFormat.SpaceAfter = 6

    try {
        $paragraph.Range.ListFormat.RemoveNumbers()
    } catch {
        Write-Verbose "The explanation paragraph did not contain list formatting."
    }
}

function Compact-BlankParagraphsBefore {
    param(
        [Parameter(Mandatory = $true)]
        $Document,
        [Parameter(Mandatory = $true)]
        [string]$Marker
    )

    $markerIndex = Find-ParagraphIndex -Document $Document -ExactText $Marker
    for ($index = $markerIndex - 1; $index -ge 1; $index--) {
        $paragraph = $Document.Paragraphs.Item($index)
        $text = ($paragraph.Range.Text -replace "\s+", " ").Trim([char]13, [char]7, " ", "`t")
        if ($text) {
            break
        }
        $paragraph.Range.Font.Size = 1
        $paragraph.Range.ParagraphFormat.SpaceBefore = 0
        $paragraph.Range.ParagraphFormat.SpaceAfter = 0
        $paragraph.Range.ParagraphFormat.LineSpacing = 1
    }
}

if (-not (Test-Path -LiteralPath $TemplatePath -PathType Leaf)) {
    throw "Template not found: $TemplatePath"
}

$health = Invoke-RestMethod -Uri "$BridgeUrl/health" -TimeoutSec 10
if ($health.status -ne "UP") {
    throw "Visual Paradigm bridge is not UP at $BridgeUrl."
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

if ((Test-Path -LiteralPath $OutputPath) -and -not $Force) {
    throw "Output already exists. Re-run with -Force to replace it: $OutputPath"
}

$assetDirectory = Join-Path $PSScriptRoot "build\submission-assets"
New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null

if ($WithoutImages) {
    $classImage = $null
    $sequenceImage = $null
    $stateImage = $null
} elseif ($UseNativeExport) {
    $classImage = Join-Path $assetDirectory "q1-class-native-export.png"
    $sequenceImage = Join-Path $assetDirectory "q2-sequence-native-export.png"
    $stateImage = Join-Path $assetDirectory "q3-state-native-export.png"

    Invoke-VpTool -ToolName "exportDiagramImage" -Arguments @{
        diagramName = $ClassDiagramName
        outputPath = $classImage
        imageFormat = "PNG"
        scalePercent = 150
        overwrite = $true
    }
    Invoke-VpTool -ToolName "exportDiagramImage" -Arguments @{
        diagramName = $SequenceDiagramName
        outputPath = $sequenceImage
        imageFormat = "PNG"
        scalePercent = 150
        overwrite = $true
    }
    Invoke-VpTool -ToolName "exportDiagramImage" -Arguments @{
        diagramName = $StateDiagramName
        outputPath = $stateImage
        imageFormat = "PNG"
        scalePercent = 150
        overwrite = $true
    }
} else {
    $classImage = if ($ClassImagePath) {
        $ClassImagePath
    } else {
        Join-Path $assetDirectory "manual-q1-class.png"
    }
    $sequenceImage = if ($SequenceImagePath) {
        $SequenceImagePath
    } else {
        Join-Path $assetDirectory "manual-q2-sequence.png"
    }
    $stateImage = if ($StateImagePath) {
        $StateImagePath
    } else {
        Join-Path $assetDirectory "manual-q3-state.png"
    }

    foreach ($imagePath in @($classImage, $sequenceImage, $stateImage)) {
        if (-not (Test-Path -LiteralPath $imagePath -PathType Leaf)) {
            throw "Clean screenshot not found: $imagePath"
        }
    }
}

Copy-Item -LiteralPath $TemplatePath -Destination $OutputPath -Force:$Force
Unblock-File -LiteralPath $OutputPath

$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0

    $document = $word.Documents.Open($OutputPath)

    $document.Tables.Item(1).Cell(2, 2).Range.Text = $DateTimeText
    $document.Tables.Item(1).Cell(3, 2).Range.Text = $ExamCode

    Insert-PageBreakBefore `
        -Document $document `
        -Marker "Question 2: Sequence diagram (4.0 points)"

    if (-not $WithoutImages) {
        Insert-DiagramImage `
            -Document $document `
            -Marker "Your  Class Diagram:" `
            -ImagePath (Resolve-Path -LiteralPath $classImage).Path `
            -AlternativeText "Q1 EMS design class diagram" `
            -MaxWidthPoints 610 `
            -MaxHeightPoints 340

        Insert-DiagramImage `
            -Document $document `
            -Marker "Your Diagram (Sequence Diagram):" `
            -ImagePath (Resolve-Path -LiteralPath $sequenceImage).Path `
            -AlternativeText "Q2 register for event sequence diagram" `
            -MaxWidthPoints 610 `
            -MaxHeightPoints 325

        Insert-DiagramImage `
            -Document $document `
            -Marker "Your Diagram (Statechart Diagram):" `
            -ImagePath (Resolve-Path -LiteralPath $stateImage).Path `
            -AlternativeText "Q3 event lifecycle state machine diagram" `
            -MaxWidthPoints 610 `
            -MaxHeightPoints 220
    }

    Insert-Explanation `
        -Document $document `
        -Occurrence 1 `
        -Text "The design separates domain, service, and persistence responsibilities. Abstract User is generalized by Student, Lecturer, Organizer, and Admin; Event and Registration carry status data, while services depend on repositories that realize Repository<T> and the associations show required multiplicities and ownership."

    Insert-Explanation `
        -Document $document `
        -Occurrence 2 `
        -Text "The Student/Lecturer submits a request through StudentPortalUI. EventRegistrationService loads the Event through EventRepository, checks registration status and capacity, then creates and saves Registration through RegistrationRepository. Nested alt fragments cover open/closed and available/full outcomes, producing either confirmation or a clear rejection."

    Insert-Explanation `
        -Document $document `
        -Occurrence 3 `
        -Text "The Event moves from Draft through approval, registration, execution, and completion. Capacity can move the event to Full, while guarded start transitions lead to Ongoing. Rejected and Cancelled are alternative terminal branches that converge on the final state."

    Compact-BlankParagraphsBefore -Document $document -Marker "END OF SUBMISSION"

    $document.Save()
    $pageCount = $document.ComputeStatistics(2)
    Write-Host "Completed Word submission ($pageCount pages): $OutputPath"
} finally {
    if ($null -ne $document) {
        $document.Close($wdDoNotSaveChanges)
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)
    }
    if ($null -ne $word) {
        $word.Quit()
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
