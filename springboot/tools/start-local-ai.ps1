<#
.SYNOPSIS
  내 PC에서 AI 문제 생성 서버를 "DB 없이" 띄운다 — 샘플 뽑기(tools/make-samples.mjs) 용.

.DESCRIPTION
  · DB 자동 구성을 빼고 DB 주소를 닫힌 포트로 덮어써서, config/application.properties 에 실제 DB 값이 있어도 운영 DB 에 붙지 않는다
    (springboot/CLAUDE.md "DB 없이 앱 띄우기" 와 같은 설정이다).
  · 로그인 · 호출 횟수 제한이 없는 서버라서 127.0.0.1 에서만 연다 — 같은 와이파이의 다른 사람이 부를 수 없다.
  · mock(기본): 가짜 모델, 비용 없음. bedrock: 실제 호출, 호출마다 요금이 든다.
  · AWS 키는 받지 않는다. 자격 증명은 AWS 기본 경로(환경변수 · ~/.aws · SSO 로그인 · -AwsProfile)로 찾는다.
  · 끝나면(Ctrl+C) 이 스크립트가 바꾼 환경변수를 원래대로 돌려 놓는다.

.EXAMPLE
  .\tools\start-local-ai.ps1                       # 가짜 모델 (파이프라인 확인용)

.EXAMPLE
  .\tools\start-local-ai.ps1 -Mode bedrock -Region <리전> -BasicModel <1·2단계용 모델 ID> -AdvancedModel <3단계용 모델 ID> -AwsProfile <프로필 이름>
#>
[CmdletBinding()]
param(
    [ValidateSet('mock', 'bedrock')][string]$Mode = 'mock',
    [int]$Port = 18080,
    [string]$Region,
    [string]$BasicModel,
    [string]$AdvancedModel,
    [string]$AwsProfile,
    [switch]$NoVerify
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot

if ($Mode -eq 'bedrock') {
    $missing = @()
    if (-not $Region) { $missing += '-Region' }
    if (-not $BasicModel) { $missing += '-BasicModel' }
    if (-not $AdvancedModel) { $missing += '-AdvancedModel' }
    if ($missing.Count -gt 0) {
        throw ("bedrock 모드에는 " + ($missing -join ', ') + " 가 필요합니다. Bedrock 콘솔의 모델 ID(또는 추론 프로필 ID)와 리전을 그대로 넣으세요. " +
            "1·2단계는 Haiku급, 3단계는 Sonnet급을 권합니다(사양서 10쪽).")
    }
    $hasCreds = $env:AWS_ACCESS_KEY_ID -or $env:AWS_PROFILE -or $AwsProfile -or
        (Test-Path (Join-Path $HOME '.aws\credentials')) -or (Test-Path (Join-Path $HOME '.aws\config'))
    if (-not $hasCreds) {
        Write-Warning "AWS 자격 증명을 찾지 못했습니다(환경변수 · ~/.aws · -AwsProfile). SSO 등 다른 경로를 쓰는 거라면 무시해도 됩니다. 없으면 첫 호출에서 502 가 납니다."
    }
}

# JDK 가 PATH 에 없는 PC 를 위해 (이 PC 의 설치 위치)
if (-not $env:JAVA_HOME) {
    $jdk = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'
    if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
}

$names = 'SERVER_PORT', 'SERVER_ADDRESS', 'DB_PASSWORD', 'SPRING_DATASOURCE_URL', 'SPRING_AUTOCONFIGURE_EXCLUDE',
    'AI_CLIENT', 'BEDROCK_REGION', 'BEDROCK_BASIC_MODEL_ID', 'BEDROCK_ADVANCED_MODEL_ID', 'AI_VERIFY', 'AWS_PROFILE', 'JAVA_HOME'
$saved = @{}
foreach ($n in $names) { $saved[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }

try {
    $env:SERVER_PORT = "$Port"
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:DB_PASSWORD = 'none'                                       # 비밀번호 점검용 자리 값 — DB 에 붙지 않는다
    $env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:1/none'    # 닫힌 포트: config 파일이 있어도 환경변수가 우선한다
    $env:SPRING_AUTOCONFIGURE_EXCLUDE = 'org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration'
    $env:AI_CLIENT = $Mode
    $env:AI_VERIFY = if ($NoVerify) { 'false' } else { 'true' }
    if ($Mode -eq 'bedrock') {
        $env:BEDROCK_REGION = $Region
        $env:BEDROCK_BASIC_MODEL_ID = $BasicModel
        $env:BEDROCK_ADVANCED_MODEL_ID = $AdvancedModel
        if ($AwsProfile) { $env:AWS_PROFILE = $AwsProfile }
    }

    Write-Host ""
    Write-Host "모드: $Mode   주소: http://127.0.0.1:$Port   2차 풀이(검증): $($env:AI_VERIFY)" -ForegroundColor Cyan
    if ($Mode -eq 'bedrock') {
        Write-Host "실제 Bedrock 호출입니다 — 문항 1개에 생성 1회 + 2차 풀이 1회(재생성 시 더). 로그인이 없으니 이 창을 열어 둔 동안 이 PC 안에서만 쓰세요." -ForegroundColor Yellow
    }
    Write-Host "뜨고 나면 다른 창에서:  node tools\make-samples.mjs --label <이름> --note ""<쓴 모델>""" -ForegroundColor Cyan
    Write-Host "끄기: 이 창에서 Ctrl+C" -ForegroundColor Cyan
    Write-Host ""

    Push-Location $project
    try { & (Join-Path $project 'gradlew.bat') bootRun } finally { Pop-Location }
}
finally {
    foreach ($n in $names) { [Environment]::SetEnvironmentVariable($n, $saved[$n], 'Process') }
}
