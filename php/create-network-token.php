<?php

declare(strict_types=1);

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;
use GlobalPayments\Api\ServiceConfigs\Gateways\GpApiConfig;
use GlobalPayments\Api\ServicesContainer;
use GlobalPayments\Api\PaymentMethods\CreditCardData;
use GlobalPayments\Api\Entities\Enums\Environment;
use GlobalPayments\Api\Entities\Enums\Channel;

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed']);
    exit;
}

try {
    $dotenv = Dotenv::createImmutable(__DIR__);
    $dotenv->load();

    $input = json_decode(file_get_contents('php://input'), true);

    if (empty($input['payment_reference'])) {
        throw new Exception('Missing payment_reference from Drop-In UI');
    }

    $config = new GpApiConfig();
    $config->appId = $_ENV['GP_API_APP_ID'] ?? '';
    $config->appKey = $_ENV['GP_API_APP_KEY'] ?? '';
    $config->environment = ($_ENV['GP_API_ENVIRONMENT'] ?? 'sandbox') === 'production'
        ? Environment::PRODUCTION
        : Environment::TEST;
    $config->channel = Channel::CardNotPresent;
    $config->country = 'US';

    ServicesContainer::configureService($config);

    $card = new CreditCardData();
    $card->token = $input['payment_reference'];

    $response = $card->tokenize(true, 'USE_NETWORK_TOKEN')
        ->withCurrency('USD')
        ->execute();

    if (empty($response->token)) {
        http_response_code(422);
        echo json_encode(['success' => false, 'message' => 'Token not returned by gateway']);
        exit;
    }

    $tokenData = [
        'id' => $response->token ?? '',
        'brand' => $response->cardType ?? '',
        'masked_card' => $response->cardLast4 ?? '',
        'usage_mode' => $response->tokenUsageMode ?? 'USE_NETWORK_TOKEN',
        'status' => $response->responseMessage ?? '',
        'created_at' => date('c')
    ];

    // Store in JSON file
    $dataDir = __DIR__ . '/data';
    $dataFile = $dataDir . '/tokens.json';
    if (!is_dir($dataDir)) {
        mkdir($dataDir, 0755, true);
    }
    $tokens = file_exists($dataFile) ? json_decode(file_get_contents($dataFile), true) : [];
    $tokens[] = $tokenData;
    file_put_contents($dataFile, json_encode($tokens, JSON_PRETTY_PRINT));

    echo json_encode([
        'success' => true,
        'data' => $tokenData,
        'message' => 'Network token created successfully'
    ]);

} catch (\GlobalPayments\Api\Entities\Exceptions\GatewayException $e) {
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'message' => 'Gateway error: ' . $e->getMessage()
    ]);
} catch (\GlobalPayments\Api\Entities\Exceptions\ApiException $e) {
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'message' => 'API error: ' . $e->getMessage()
    ]);
} catch (Exception $e) {
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'message' => $e->getMessage()
    ]);
}
