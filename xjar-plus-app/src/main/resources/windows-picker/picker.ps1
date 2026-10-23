# @author kongweiguang
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('file', 'save', 'directory')]
    [string]$Kind
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::Error.WriteLine('XJP_PICKER_STAGE=helper-compile-enter')
[Console]::Error.Flush()
Add-Type -Path (Join-Path $PSScriptRoot 'NativeFileDialog.cs')
[Console]::Error.WriteLine('XJP_PICKER_STAGE=helper-compile-return')
[Console]::Error.Flush()
[Console]::Error.WriteLine('XJP_PICKER_STAGE=helper-call-enter')
[Console]::Error.Flush()
$selected = [XjpNativeFileDialog]::Choose($Kind)
[Console]::Error.WriteLine('XJP_PICKER_STAGE=helper-call-return')
[Console]::Error.Flush()
if ($null -eq $selected) {
    exit 2
}
[Console]::Out.Write($selected)
exit 0
