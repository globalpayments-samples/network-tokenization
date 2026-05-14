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

    if (empty($input['pmt_id'])) {
        throw new Exception('Missing pmt_id');
    }

    $amount = floatval($input['amount'] ?? 0);
    if ($amount <= 0) {
        throw new Exception('Invalid amount');
    }

    $currency = $input['currency'] ?? 'USD';

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
    $card->token = $input['pmt_id'];

    $response = $card->charge($amount)
        ->withCurrency($currency)
        ->withPaymentMethodUsageMode('USE_NETWORK_TOKEN')
        ->execute();

    if ($response->responseCode === 'SUCCESS') {
        $tokenUsageMode = $response->tokenUsageMode ?? '';
        if (empty($tokenUsageMode)) {
            $dataFile = __DIR__ . '/data/tokens.json';
            if (file_exists($dataFile)) {
                $storedTokens = json_decode(file_get_contents($dataFile), true) ?? [];
                foreach ($storedTokens as $storedToken) {
                    if (($storedToken['id'] ?? '') === $input['pmt_id']) {
                        $tokenUsageMode = $storedToken['usage_mode'] ?? '';
                        break;
                    }
                }
            }
        }

        echo json_encode([
            'success' => true,
            'data' => [
                'transactionId' => $response->transactionId,
                'status' => $response->responseMessage,
                'amount' => $amount,
                'currency' => $currency,
                'authCode' => $response->authorizationCode ?? '',
                'reference' => $response->referenceNumber ?? '',
                'tokenUsageMode' => $tokenUsageMode
            ],
            'message' => 'Payment processed successfully'
        ]);
    } else {
        throw new Exception('Transaction declined: ' . ($response->responseMessage ?? 'Unknown error'));
    }

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
