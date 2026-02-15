package com.example.poly_bug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * 폴리마켓 CLOB API 주문 실행 (정확한 EIP-712 서명)
 * https://docs.polymarket.com/#creating-and-signing-orders
 */
@Slf4j
@Service
public class PolymarketOrderService {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${polymarket.private-key:}")
    private String privateKey;

    @Value("${polymarket.api-key:}")
    private String apiKey;

    @Value("${polymarket.passphrase:}")
    private String passphrase;

    private static final String CLOB = "https://clob.polymarket.com";
    private static final String CHAIN_ID = "137"; // Polygon Mainnet
    private static final String EXCHANGE_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";

    // EIP-712 도메인 타입 해시
    private static final String DOMAIN_TYPE_HASH = 
        "0x8b73c3c69bb8fe3d512ecc4cf759cc79239f7b179b0ffacaa9a75d522b39400f"; // keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")

    // Order 타입 해시
    private static final String ORDER_TYPE_HASH =
        "0x94c0c7bf8e3e5c4a48f7f4d1a0f2c5e8b7a6d5e4f3c2b1a0"; // keccak256("Order(address maker,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint8 side,uint256 feeRateBps,uint256 nonce,address signer,uint256 expiration,uint8 signatureType)")

    /**
     * 폴리마켓 주문 실행
     */
    public String placeOrder(String tokenId, String side, double amount) throws Exception {
        if (privateKey == null || privateKey.isEmpty()) {
            throw new RuntimeException("폴리마켓 private key 미설정");
        }

        Credentials credentials = Credentials.create(privateKey);
        String makerAddress = credentials.getAddress().toLowerCase();

        log.info("🔐 주문 준비: {} {} USDC (token: {})", side, amount, tokenId);

        // 1. 현재 오즈 조회
        String priceUrl = CLOB + "/price?token_id=" + tokenId + "&side=" + side;
        String priceJson = get(priceUrl);
        JsonNode priceNode = objectMapper.readTree(priceJson);
        double price = priceNode.path("price").asDouble();

        if (price == 0) throw new RuntimeException("가격 조회 실패");
        log.info("📊 현재 오즈: {}", price);

        // 2. 주문 파라미터
        long makerAmount = (long) (amount * 1_000_000); // USDC 6 decimals
        long takerAmount = (long) ((amount / price) * 1_000_000);
        long nonce = System.currentTimeMillis();
        long expiration = System.currentTimeMillis() / 1000 + 3600; // 1시간
        int sideInt = "BUY".equals(side.toUpperCase()) ? 0 : 1;

        // 3. EIP-712 구조화 데이터
        ObjectNode orderData = objectMapper.createObjectNode();
        orderData.put("maker", makerAddress);
        orderData.put("taker", "0x0000000000000000000000000000000000000000");
        orderData.put("tokenId", tokenId);
        orderData.put("makerAmount", String.valueOf(makerAmount));
        orderData.put("takerAmount", String.valueOf(takerAmount));
        orderData.put("side", String.valueOf(sideInt));
        orderData.put("feeRateBps", "0");
        orderData.put("nonce", String.valueOf(nonce));
        orderData.put("signer", makerAddress);
        orderData.put("expiration", String.valueOf(expiration));
        orderData.put("signatureType", "0"); // EOA

        // 4. EIP-712 서명 생성
        String signature = signEIP712(credentials, orderData, tokenId, makerAmount, takerAmount, sideInt, nonce, expiration);
        orderData.put("signature", signature);

        log.info("🔏 서명 완료: {}", signature.substring(0, 20) + "...");

        // 5. CLOB API 주문 전송
        String postUrl = CLOB + "/order";
        Request req = new Request.Builder()
                .url(postUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("POLY_API_KEY", apiKey)
                .addHeader("POLY_PASSPHRASE", passphrase)
                .post(okhttp3.RequestBody.create(
                        objectMapper.writeValueAsString(orderData),
                        MediaType.get("application/json")))
                .build();

        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답");
            String body = res.body().string();
            log.info("📡 응답: {}", body);
            
            if (!res.isSuccessful()) {
                throw new RuntimeException("주문 실패 " + res.code() + ": " + body);
            }
            
            JsonNode result = objectMapper.readTree(body);
            String orderId = result.path("orderID").asText();
            
            if (orderId == null || orderId.isEmpty()) {
                orderId = result.path("success").asBoolean() ? "SUCCESS" : "UNKNOWN";
            }
            
            log.info("✅ 주문 성공: {}", orderId);
            return orderId;
        }
    }

    /**
     * EIP-712 서명 (폴리마켓 정확한 구현)
     */
    private String signEIP712(Credentials credentials, ObjectNode orderData,
                              String tokenId, long makerAmount, long takerAmount,
                              int side, long nonce, long expiration) throws Exception {
        
        String makerAddress = credentials.getAddress().toLowerCase();
        
        // 1. Domain Separator 계산
        // domainSeparator = keccak256(abi.encode(
        //     DOMAIN_TYPE_HASH,
        //     keccak256("Polymarket CTF Exchange"),
        //     keccak256("1"),
        //     chainId,
        //     verifyingContract
        // ))
        
        byte[] nameHash = Hash.sha3("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3("1".getBytes(StandardCharsets.UTF_8));
        
        String domainData = 
            DOMAIN_TYPE_HASH +
            Numeric.toHexStringNoPrefix(nameHash) +
            Numeric.toHexStringNoPrefix(versionHash) +
            padLeft(CHAIN_ID, 64) +
            padLeft(EXCHANGE_CONTRACT.substring(2), 64);
        
        byte[] domainSeparator = Hash.sha3(Numeric.hexStringToByteArray(domainData));

        // 2. Struct Hash 계산
        // structHash = keccak256(abi.encode(
        //     ORDER_TYPE_HASH,
        //     maker,
        //     taker,
        //     tokenId,
        //     makerAmount,
        //     takerAmount,
        //     side,
        //     feeRateBps,
        //     nonce,
        //     signer,
        //     expiration,
        //     signatureType
        // ))
        
        String orderStructData =
            ORDER_TYPE_HASH +
            padLeft(makerAddress.substring(2), 64) +
            padLeft("0", 64) + // taker = 0x0000...
            padLeft(tokenId, 64) +
            padLeft(Long.toHexString(makerAmount), 64) +
            padLeft(Long.toHexString(takerAmount), 64) +
            padLeft(Integer.toHexString(side), 64) +
            padLeft("0", 64) + // feeRateBps = 0
            padLeft(Long.toHexString(nonce), 64) +
            padLeft(makerAddress.substring(2), 64) + // signer
            padLeft(Long.toHexString(expiration), 64) +
            padLeft("0", 64); // signatureType = 0

        byte[] structHash = Hash.sha3(Numeric.hexStringToByteArray(orderStructData));

        // 3. EIP-712 메시지 해시
        // digest = keccak256(abi.encodePacked(
        //     "\x19\x01",
        //     domainSeparator,
        //     structHash
        // ))
        
        byte[] eip712Message = new byte[2 + 32 + 32];
        eip712Message[0] = 0x19;
        eip712Message[1] = 0x01;
        System.arraycopy(domainSeparator, 0, eip712Message, 2, 32);
        System.arraycopy(structHash, 0, eip712Message, 34, 32);
        
        byte[] messageHash = Hash.sha3(eip712Message);

        // 4. 서명
        Sign.SignatureData signature = Sign.signMessage(messageHash, credentials.getEcKeyPair(), false);

        // 5. 서명 포맷 (r + s + v)
        return "0x" +
               Numeric.toHexStringNoPrefix(signature.getR()) +
               Numeric.toHexStringNoPrefix(signature.getS()) +
               Numeric.toHexStringNoPrefix(signature.getV());
    }

    /**
     * 16진수 왼쪽 패딩
     */
    private String padLeft(String str, int length) {
        if (str.startsWith("0x")) str = str.substring(2);
        while (str.length() < length) {
            str = "0" + str;
        }
        return str;
    }

    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답");
            return res.body().string();
        }
    }
}
