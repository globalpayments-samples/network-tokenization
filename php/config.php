<?php

declare(strict_types=1);

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;
use GlobalPayments\Api\ServiceConfigs\Gateways\GpApiConfig;
use GlobalPayments\Api\ServicesContainer;
use GlobalPayments\Api\Entities\Enums\Environment;
use GlobalPayments\Api\Entities\Enums\Channel;
use GlobalPayments\Api\Services\GpApiService;

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

try {
    $dotenv = Dotenv::createImmutable(__DIR__);
    $dotenv->load();

    $config = new GpApiConfig();
    $config->appId = $_ENV['GP_API_APP_ID'] ?? '';
    $config->appKey = $_ENV['GP_API_APP_KEY'] ?? '';
    $config->environment = ($_ENV['GP_API_ENVIRONMENT'] ?? 'sandbox') === 'production'
        ? Environment::PRODUCTION
        : Environment::TEST;
    $config->channel = Channel::CardNotPresent;
    $config->country = 'US';
    $config->permissions = ['PMT_POST_Create_Single'];

    ServicesContainer::configureService($config);

    $sessionToken = GpApiService::generateTransactionKey($config);

    if (!is_object($sessionToken) || empty($sessionToken->accessToken)) {
        throw new Exception('Failed to generate access token');
    }

    echo json_encode([
        'success' => true,
        'data' => [
            'accessToken' => $sessionToken->accessToken
        ]
    ]);
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'message' => 'Configuration error: ' . $e->getMessage()
    ]);
}
