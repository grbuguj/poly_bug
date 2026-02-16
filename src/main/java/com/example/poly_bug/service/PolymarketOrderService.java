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

    // EIP-712 도메인 타입 해시 (keccak256 of type string)
    // keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")
    // 이 값은 web3j Hash.sha3()로 런타임 계산하므로 상수 불필요

    // Order 타입 문자열 (Polymarket CTF Exchange 공식 스펙)
    private static final String ORDER_TYPE_STRING =
        "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)";

    // 런타임 계산
    private static final byte[] ORDER_TYPE_HASH_BYTES = Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
    private static final byte[] DOMAIN_TYPE_HASH_BYTES = Hash.sha3(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8));

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
     * EIP-712 서명 (Polymarket CTF Exchange 공식 스펙)
     */
    private String signEIP712(Credentials credentials, ObjectNode orderData,
                              String tokenId, long makerAmount, long takerAmount,
                              int side, long nonce, long expiration) throws Exception {
        
        String makerAddress = credentials.getAddress().toLowerCase();
        long salt = System.nanoTime(); // 고유 salt
        orderData.put("salt", String.valueOf(salt));
        
        // 1. Domain Separator
        byte[] nameHash = Hash.sha3("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3("1".getBytes(StandardCharsets.UTF_8));
        
        // abi.encode(domainTypeHash, nameHash, versionHash, chainId, verifyingContract)
        byte[] domainEncoded = new byte[32 * 5];
        System.arraycopy(DOMAIN_TYPE_HASH_BYTES, 0, domainEncoded, 0, 32);
        System.arraycopy(nameHash, 0, domainEncoded, 32, 32);
        System.arraycopy(versionHash, 0, domainEncoded, 64, 32);
        encodeUint256(domainEncoded, 96, BigInteger.valueOf(137)); // chainId
        encodeAddress(domainEncoded, 128, EXCHANGE_CONTRACT);
        
        byte[] domainSeparator = Hash.sha3(domainEncoded);

        // 2. Struct Hash (공식 Order 스펙: salt, maker, signer, taker, tokenId, makerAmount, takerAmount, expiration, nonce, feeRateBps, side, signatureType)
        byte[] structEncoded = new byte[32 * 13]; // typeHash + 12 fields
        System.arraycopy(ORDER_TYPE_HASH_BYTES, 0, structEncoded, 0, 32);
        encodeUint256(structEncoded, 32, BigInteger.valueOf(salt));
        encodeAddress(structEncoded, 64, makerAddress);
        encodeAddress(structEncoded, 96, makerAddress); // signer = maker
        encodeAddress(structEncoded, 128, "0x0000000000000000000000000000000000000000"); // taker
        encodeUint256(structEncoded, 160, new BigInteger(tokenId.length() > 40 ? tokenId : "0", tokenId.matches("\\d+") ? 10 : 16));
        encodeUint256(structEncoded, 192, BigInteger.valueOf(makerAmount));
        encodeUint256(structEncoded, 224, BigInteger.valueOf(takerAmount));
        encodeUint256(structEncoded, 256, BigInteger.valueOf(expiration));
        encodeUint256(structEncoded, 288, BigInteger.valueOf(nonce));
        encodeUint256(structEncoded, 320, BigInteger.ZERO); // feeRateBps = 0
        encodeUint256(structEncoded, 352, BigInteger.valueOf(side)); // side as uint8 padded
        encodeUint256(structEncoded, 384, BigInteger.ZERO); // signatureType = 0 (EOA)

        byte[] structHash = Hash.sha3(structEncoded);

        // 3. EIP-712 digest = keccak256("\x19\x01" + domainSeparator + structHash)
        byte[] eip712Message = new byte[2 + 32 + 32];
        eip712Message[0] = 0x19;
        eip712Message[1] = 0x01;
        System.arraycopy(domainSeparator, 0, eip712Message, 2, 32);
        System.arraycopy(structHash, 0, eip712Message, 34, 32);
        
        byte[] messageHash = Hash.sha3(eip712Message);

        // 4. 서명
        Sign.SignatureData signature = Sign.signMessage(messageHash, credentials.getEcKeyPair(), false);

        // 5. r + s + v
        return "0x" +
               Numeric.toHexStringNoPrefix(signature.getR()) +
               Numeric.toHexStringNoPrefix(signature.getS()) +
               Numeric.toHexStringNoPrefix(signature.getV());
    }

    private void encodeUint256(byte[] dest, int offset, BigInteger value) {
        byte[] bytes = value.toByteArray();
        // 32바이트 패딩 (big-endian)
        int start = offset + 32 - bytes.length;
        if (bytes[0] == 0 && bytes.length > 1) {
            // 부호 바이트 제거
            System.arraycopy(bytes, 1, dest, offset + 32 - bytes.length + 1, bytes.length - 1);
        } else {
            System.arraycopy(bytes, 0, dest, Math.max(start, offset), Math.min(bytes.length, 32));
        }
    }

    private void encodeAddress(byte[] dest, int offset, String address) {
        String clean = address.startsWith("0x") ? address.substring(2) : address;
        byte[] addrBytes = Numeric.hexStringToByteArray(clean);
        // 주소는 20바이트 → 왼쪽 12바이트 패딩
        System.arraycopy(addrBytes, 0, dest, offset + 12, 20);
    }

    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답");
            return res.body().string();
        }
    }
}
