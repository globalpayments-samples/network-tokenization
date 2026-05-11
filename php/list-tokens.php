<?php

declare(strict_types=1);

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

$dataFile = __DIR__ . '/data/tokens.json';
$tokens = file_exists($dataFile) ? json_decode(file_get_contents($dataFile), true) : [];

echo json_encode([
    'success' => true,
    'data' => $tokens
]);
