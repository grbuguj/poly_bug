# 🤖 CLAUDE_WORK_LOG.md
> **⚠️ Claude는 작업 전 반드시 이 파일을 먼저 읽을 것!**
> 마지막 업데이트: 2026-02-13

---

## 📌 프로젝트 개요

| 항목 | 내용 |
|------|------|
| 프로젝트명 | **PolyBug** - BTC/ETH 1H/15M 자동 트레이딩 봇 |
| 경로 | `/Users/jaeung/IdeaProjects/poly_bug/` |
| 프레임워크 | Spring Boot 4.0.2, Java 17 |
| 빌드 | Gradle |
| DB | H2 (파일 기반: `./poly-trading-bot`) |
| 포트 | 8080 |
| AI 엔진 | Claude Sonnet 4.5 (`claude-sonnet-4-5-20250929`) |
| 사용자 목표 | Polymarket BTC/ETH Up/Down 마켓에 자동 배팅하여 수익 창출 (빚 100억 상환) |
| 현재 모드 | **DRY-RUN** (시뮬레이션 배팅, 실제 주문 없음) |
| 지원 타임프레임 | **1H** (1시간) + **15M** (15분) |
| 시뮬레이션 시작 자금 | $50.00 |
| 대시보드 | `http://localhost:8080` |

---

## 🏗️ 시스템 아키텍처

### 전체 플로우
```
스케줄러 (Cron 트리거)
    ↓
TradingService.executeCycle(coin, timeframe, minEvThreshold)
    ↓
1. MarketDataService.collect(coin)          → 바이낸스 API: 가격/변화율/RSI/MACD/펀딩비/OI/롱숏비/공포탐욕
2. PolymarketOddsService.getOdds(coin, tf)  → 폴리마켓 CLOB API: 실시간 UP/DOWN 오즈
3. ClaudeDecisionEngine.decide()            → Claude API: 시장지표+과거패턴 → UP/DOWN/HOLD 판단
4. ExpectedValueCalculator.calculate()      → EV 계산 + Kelly Criterion 배팅 사이즈
5. 배팅 실행 (DRY-RUN: 로그만 / LIVE: 실주문) → PolymarketOrderService
6. Trade 엔티티 DB 저장 + WebSocket 브로드캐스트
    ↓
TradeResultChecker (1분마다)
    → 캔들 종료 시점(정시/15분 단위) 지난 PENDING 배팅
    → 바이낸스 캔들 시가 vs 종가 비교 → WIN/LOSE 판정
    → BalanceService 잔액 반영 + WebSocket 알림
```

### 마켓 판정 로직 (핵심)
```
Polymarket 판정 기준:
  - 캔들 시가(Open) vs 캔들 종가(Close) 비교
  - Close > Open → UP 승리 (BUY_YES WIN)
  - Close < Open → DOWN 승리 (BUY_NO WIN)
  - Close == Open → DOWN 승리

1H 마켓: ET(미국 동부시간) 기준 정시 슬러그 (예: "btc-up-or-down-2pm-et")
15M 마켓: Unix 타임스탬프 기반 슬러그 (예: "btc-up-or-down-1707825600")
가격 소스: Chainlink BTC/USD, ETH/USD (바이낸스 아님!)
```

### 트리거 시스템
```
각 코인×타임프레임마다 2개 트리거:
  🔍 탐색 트리거: 높은 EV 기준, 신중한 진입
  ✅ 확인 트리거: 낮은 EV 기준, 보완 진입

1H: 매시 XX분에 실행 (예: :36, :49)
15M: 15분 윈도우 내 오프셋 (예: 3분, 8분)

72시간 과거 데이터 자동 분석 → 최적 트리거 분/EV 임계값 결정
서버 시작시 TriggerInitializer가 자동 분석 실행
```

---

## 🗂️ 파일 맵

### config/
| 파일 | 역할 |
|------|------|
| `WebSocketConfig.java` | STOMP+SockJS WebSocket. `/ws` 엔드포인트, `/topic/trading` 구독 |

### controller/
| 파일 | 역할 | 주요 엔드포인트 |
|------|------|----------------|
| `DashboardController.java` | 대시보드 + REST API | `GET /` `POST /trade/run?coin=&timeframe=` `GET /indicators?coin=` `GET /stats?coin=&timeframe=` `GET /trades?coin=&timeframe=` `GET /odds?coin=&timeframe=` `GET /trigger-config/all` `GET /balance` `GET /balance/history` `DELETE /trades/all` |

### dto/
| 파일 | 역할 |
|------|------|
| `MarketData.java` | Polymarket 마켓 데이터 DTO |
| `MarketIndicators.java` | 시장 지표 DTO (1H+15M RSI/MACD, 가격, 변화율, 펀딩비, OI, 롱숏비, 공포탐욕, 시초가) |
| `TradeDecision.java` | Claude 판단 결과 DTO (action, confidence, amount, reason, coin, timeframe) |
| `MarketOdds.java` | 폴리마켓 오즈 record (upOdds, downOdds, marketId, slug, available) |

### entity/
| 파일 | 역할 |
|------|------|
| `Trade.java` | 배팅 기록 엔티티. action, result, confidence, entryPrice, exitPrice, **openPrice**(시초가), betAmount, profitLoss, coin, **timeframe**, 시장지표 스냅샷 |
| `ReflectionLog.java` | AI 자기반성 로그 엔티티 |

### repository/
| 파일 | 역할 |
|------|------|
| `TradeRepository.java` | Trade CRUD + 통계 쿼리 (코인별, 타임프레임별, 레거시 null 호환) |
| `ReflectionLogRepository.java` | ReflectionLog CRUD |

### scheduler/
| 파일 | 역할 |
|------|------|
| `BtcTradingScheduler.java` | BTC 1H 2-트리거 스케줄링 (동적 분 설정) |
| `EthTradingScheduler.java` | ETH 1H 2-트리거 스케줄링 |
| `TradeResultChecker.java` | **1분마다** PENDING 체크 → 캔들 종료 후 시가 vs 종가 비교 → WIN/LOSE 판정 |
| `TriggerInitializer.java` | 서버 시작시 72H 과거 분석 자동 실행 (BTC/ETH × 1H/15M) |

### service/
| 파일 | 역할 |
|------|------|
| `TradingService.java` | **핵심 로직**. executeCycle(coin, timeframe, minEvThreshold) → 지표수집→오즈→Claude→EV→배팅→저장 |
| `ClaudeDecisionEngine.java` | Claude API 호출. **코인별/타임프레임별** 맞춤 프롬프트 생성 → ACTION/CONFIDENCE/AMOUNT/REASON 파싱 |
| `MarketDataService.java` | 바이낸스 API 데이터 수집: 가격, 1H/4H/24H 변화율, RSI(1H+15M), MACD(1H+15M), 펀딩비, OI, 롱숏비, 공포탐욕, 시초가(1H+15M) |
| `ExpectedValueCalculator.java` | EV 계산 + Kelly Criterion 배팅금액. 동적 임계값, CLOB 극단 오즈 클램핑 [15%, 85%] |
| `PolymarketOddsService.java` | Polymarket Gamma/CLOB API. 1H(ET 슬러그) + 15M(Unix 타임스탬프 슬러그) 지원 |
| `BalanceService.java` | 시뮬레이션 잔액 관리. $50 시작, deductBet/addWinnings/recalcFromDb/getProfitPct |
| `TriggerConfigService.java` | 코인×타임프레임별 트리거 설정 관리 (분, EV임계값, 소스, 대기열) |
| `TimingAnalysisService.java` | 72H 과거 바이낸스 캔들 분석 → 최적 트리거 분/EV 자동 결정 (1H: 분 단위, 15M: 윈도우 내 오프셋) |
| `BotStateService.java` | 봇 상태 관리 (항상 자동 실행) |
| `SelfReflectionService.java` | 배팅 결과 후 Claude 반성 요청 |
| `PolymarketClient.java` | Polymarket 잔액/마켓 조회 (현재 mock) |
| `PolymarketOrderService.java` | 실제 주문 실행 (LIVE 모드용) |

### resources/
| 파일 | 역할 |
|------|------|
| `application.properties` | 서버/DB/트레이딩/API키 설정 |
| `templates/dashboard.html` | Thymeleaf 대시보드 (탭 UI, TradingView 차트, 실시간 지표, 배팅기록, 포트폴리오, 로그 사이드바) |

---

## 🎨 대시보드 UI 구조

```
┌─────────────────────────────────────────────────────────────────┐
│ 🤖 PolyBug  [DRY-RUN]  PNL: +$20.18  💎 $70.18   ⚡BTC ⚡ETH  │
├─────────────────────────────────────────────────────────────────┤
│ [⏱ 1시간 (13)]  [⏱ 15분 (5)]  ← 탭 전환                       │
├─────────────────────────────────────────────┬───────────────────┤
│                                             │ 📋 실시간 로그     │
│ 📡 실시간 현황 (BTC/ETH 시초가→현재가)       │                   │
│ 지표: 펀딩비 | 공포탐욕 | OI변화 | 추세 | RSI│ [WebSocket STOMP] │
│                                             │ 실시간 배팅/결과   │
│ ── 1H 탭 or 15M 탭 내용 ──                  │ 로그 표시          │
│                                             │                   │
│ 🎲 오즈 (BTC/ETH UP↑ DOWN↓ 바)             │                   │
│ 📈 TradingView 차트 (1H:60분봉/15M:15분봉)  │                   │
│ ⏱️ 트리거 (탐색/확인 2개, 시각 표시)         │                   │
│ 📊 성과 (BTC/ETH 각각: 총배팅/승률/PNL)     │                   │
│ 💼 배팅기록 (시간/방향/시초가/진입가/종가/EV)│                   │
│                                             │                   │
│ ── 공통 ──                                  │                   │
│ 💰 포트폴리오 (원금/총자산/수익률/이퀄리티)   │                   │
│ 🧪 패턴 학습                                │                   │
├─────────────────────────────────────────────┴───────────────────┤
│                        [🗑️ 전체 삭제]                           │
└─────────────────────────────────────────────────────────────────┘
```

**탭 구조:**
- **1H 탭** (초록 `#4caf50`): 1H 오즈, 1H 60분봉 차트, 1H 트리거, 1H 성과, 1H 배팅기록
- **15M 탭** (보라 `#ce93d8`): 15M 오즈, 15M 15분봉 차트, 15M 트리거, 15M 성과, 15M 배팅기록
- **공통** (항상 표시): 실시간 현황, 포트폴리오, 패턴 학습, 로그 사이드바

**실시간 업데이트 주기:**
- 가격: 1초 (Binance REST)
- 오즈: 15초
- 지표/통계: 30초
- 배팅기록: 15초
- 잔액: 15초
- 이퀄리티 커브: 60초
- 트리거: 60초

---

## ⚙️ Claude 프롬프트 엔진 (ClaudeDecisionEngine)

### 프롬프트 구성 (buildPrompt)
```
1. 역할 설정: "Polymarket '{coin} Up or Down - {tf}' 마켓 전문 트레이더"
2. 판정 기준: 시초가 vs 종가 비교 (Close > Open = UP WIN)
3. 현재 캔들 상태 (가장 중요):
   - 시초가, 현재가, 방향(UP/DOWN), 변동폭
   - ⭐ 경과 시간: "42분/60분 (잔여 18분)"
   - ⚠️ 캔들 종료 임박/초반 경고
4. 코인별 시장 지표:
   - BTC: BTC 1H/4H/24H 변화율 + ETH 1H 상관 지표
   - ETH: ETH 1H/4H/24H 변화율 + BTC 1H 상관 지표
5. 선물 시장: 펀딩비, OI 변화, 롱숏비율(⚠️ 과밀집 경고)
6. 기술적 지표 (타임프레임 맞춤):
   - 1H 판단 → 1H RSI/MACD 사용
   - 15M 판단 → 15M RSI/MACD 사용
   - MACD: 라인 + 시그널 + 히스토그램 (정석 구현)
7. 공포탐욕지수
8. 폴리마켓 오즈 (UP/DOWN 각 센트)
9. 과거 성적 (코인+타임프레임별 분리):
   - 승률, 최근 5건 방향+결과
   - UP/DOWN 별도 승률
10. 판단 규칙 (5개):
    - 시초가 vs 현재가 방향이 1순위
    - 경과 시간 가중치 (후반부 → 방향 유지 확률↑)
    - 기술적 지표 보조
    - 오즈/EV 고려
    - HOLD 적극 활용
11. 확신도 기준표 (50~95)
```

### 주요 개선 이력
| 항목 | Before | After |
|------|--------|-------|
| BTC 지표 | 1H만 | 1H/4H/24H + ETH 상관 |
| 캔들 경과 시간 | ❌ 없음 | ✅ "42분/60분 (잔여 18분)" + 종료/초반 경고 |
| 롱숏비율 | 수집만, 미전송 | ✅ 프롬프트에 포함 + 과밀집 경고 |
| 과거 성적 | 코인만 분리 (1H+15M 혼합) | ✅ 코인+타임프레임 분리 + UP/DOWN별 승률 |
| RSI/MACD 타임프레임 | 항상 1H | ✅ 15M 판단시 15M RSI/MACD 사용 |
| MACD Signal | `macdSignal = macdLine` (가짜) | ✅ EMA(9) 정석 구현 + 히스토그램 |
| RSI 계산 | 단순 SMA | ✅ Wilder's Smoothing (TradingView 동일) |
| Trend 계산 | ETH 데이터로 BTC 판단 | ✅ 코인별 독립 계산 |
| odds.source() | 🔴 컴파일 에러 | ✅ odds.slug() 수정 |
| 확신도 90+ | "방향 확인" | ✅ "방향 확인 + 캔들 후반부" |

---

## 📊 MarketIndicators 필드 목록

### 가격
| 필드 | 설명 |
|------|------|
| `ethPrice` / `btcPrice` | 현재가 (Binance SPOT) |
| `ethChange1h/4h/24h` | ETH 변화율 (현재 캔들 시가 대비) |
| `btcChange1h/4h/24h` | BTC 변화율 (1H/4H/24H) |
| `ethHourOpen` / `btcHourOpen` | 현재 1H 캔들 시가 |
| `eth15mOpen` / `btc15mOpen` | 현재 15M 캔들 시가 |

### 선물
| 필드 | 설명 |
|------|------|
| `fundingRate` | 펀딩비 (%) |
| `openInterest` | 미결제약정 |
| `openInterestChange` | OI 5분 변화율 (%) |
| `longShortRatio` | 롱/숏 비율 |

### 기술적 지표 (1H)
| 필드 | 설명 |
|------|------|
| `rsi` | RSI(14) — Wilder's Smoothing |
| `macdLine` | MACD 라인 = EMA(12) - EMA(26) |
| `macdSignal` | MACD 시그널 = MACD 라인의 EMA(9) |
| `macd` | MACD 히스토그램 = 라인 - 시그널 |

### 기술적 지표 (15M)
| 필드 | 설명 |
|------|------|
| `rsi15m` | 15M RSI(14) |
| `macdLine15m` | 15M MACD 라인 |
| `macdSignal15m` | 15M MACD 시그널 |
| `macd15m` | 15M MACD 히스토그램 |

### 심리/추세
| 필드 | 설명 |
|------|------|
| `fearGreedIndex` | 공포탐욕지수 (0~100) |
| `fearGreedLabel` | "극도 공포"/"공포"/"중립"/"탐욕"/"극도 탐욕" |
| `trend` | "UPTREND"/"DOWNTREND"/"SIDEWAYS" (코인별 독립 계산) |
| `volume1h` | 거래량 (필드 존재, 미수집) |

---

## 💰 잔액 시스템 (BalanceService)

```
초기 잔액: $50.00
배팅 시: deductBet(amount) → 잔액 차감
결과 확정:
  - WIN → addWinnings(payout) = betAmount / oddsCents (예: $2 / 0.40 = $5 수령)
  - LOSE → 이미 차감됨, 추가 작업 없음
총 자산 = 가용 잔액 + PENDING 배팅 합계
```

**이퀄리티 커브**: `/balance/history` → 배팅/결과 이벤트마다 잔액 스냅샷 기록 → Canvas 그래프 렌더링

---

## 🔧 설정 (application.properties)

```properties
server.port=8080
spring.datasource.url=jdbc:h2:./poly-trading-bot
trading.enabled=true
trading.dry-run=true                    # 실배팅 시 false
trading.balance=100.0
trading.max-bet-ratio=0.10
anthropic.api-key=${ANTHROPIC_API_KEY}
anthropic.model=claude-sonnet-4-5-20250929
```

---

## ✅ 전체 작업 이력

### Phase 0: 초기 구축 (2026-02-12 이전)
- Spring Boot 프로젝트 생성, 기본 트레이딩 플로우 구현
- Claude API 연동, 바이낸스 API 연동
- Polymarket Gamma API 오즈 조회

### Phase 1: 기반 정비 (2026-02-12)
| # | 작업 |
|---|------|
| 1 | dashboard.html "결과 업데이트" 카드 삭제 |
| 2 | CLAUDE_WORK_LOG.md 생성 |
| 3 | 3-트리거 스케줄러 적용 (BTC/ETH 각각 cron, EV 임계값, 시간당 1회 제한) |
| 4 | TradingService → boolean 반환 + minEvThreshold 파라미터 |
| 5 | 시작/정지 버튼 삭제 → 항상 자동 실행 |
| 6 | 챗봇 사이드바 → 실시간 로그 사이드바 (WebSocket STOMP+SockJS, 200줄) |
| 7 | DashboardController start/stop/status 엔드포인트 제거 |

### Phase 2: 타이밍 분석 + 인프라 (2026-02-12)
| # | 작업 |
|---|------|
| 8 | TimingAnalysisService 완성 (72H 바이낸스 캔들 분석 → 최적 트리거 분/EV) |
| 9 | TriggerConfigService 구현 (코인별 트리거 설정 관리, 대기열 시스템) |
| 10 | TriggerInitializer 구현 (서버 시작시 자동 72H 분석) |
| 11 | 트리거 3개 → 2개 축소 (탐색/확인) |
| 12 | 대시보드 UI 전면 리디자인 (지표 그리드, 트리거 시각화, EV 설명 패널) |
| 13 | Confidence calibration 가이드 테이블 추가 (55% 임계값) |

### Phase 3: 시초가 + 결과 판정 (2026-02-12)
| # | 작업 |
|---|------|
| 14 | MarketDataService: fetchCurrentHourOpen() 구현 (정시 시작가) |
| 15 | 대시보드 가격 카드: 정시 시작가 → 현재가 표시 |
| 16 | 탭 UI: 상단 BTC/ETH 탭 제거 (오즈 카드에서 직접 표시) |
| 17 | TradeResultChecker: 체크 간격 5분 → **1분**, 정시 캔들 종료 시점 기반 판정 |
| 18 | TradeResultChecker: getHourClosePrice() → 바이낸스 1H 캔들 종가 조회 |
| 19 | 대시보드 카운트다운: "정시 X분 전" 표시 |

### Phase 4: 밸런스 + 포트폴리오 (2026-02-12)
| # | 작업 |
|---|------|
| 20 | BalanceService 구현 ($50 시작, deductBet/addWinnings/recalcFromDb/getProfitPct) |
| 21 | TradingService 연동 (배팅시 잔액 차감) |
| 22 | 대시보드 헤더 잔액 배지 + 포트폴리오 카드 (원금/현재/수익률/손익) |
| 23 | 이퀄리티 커브 Canvas 그래프 |
| 24 | 총 자산 표시 (잔액 + 배팅) |
| 25 | 트리거 업데이트 시간 표시 ("🕐 09:15 (23분 전)") |

### Phase 5: EV 버그 수정 + 오즈 개선 (2026-02-12)
| # | 작업 |
|---|------|
| 26 | CLOB API 극단 오즈 클램핑 [15%, 85%] |
| 27 | EV 캡 200% 적용 |
| 28 | Kelly bet sizing 클램핑된 오즈 사용 |
| 29 | HOLD 트레이드 저장 안 함 (DB + TradeResultChecker 스킵) |

### Phase 6: 15M 마켓 지원 (2026-02-12~13)
| # | 작업 |
|---|------|
| 30 | 5분/15분 마켓 구조 조사 (Unix timestamp 슬러그, Chainlink 가격 소스) |
| 31 | PolymarketOddsService: 15M Unix timestamp 슬러그 생성 |
| 32 | MarketDataService: 15M 캔들 시초가 (fetchCurrent15mOpen, fetch15mOpenAt) |
| 33 | TriggerConfigService: 타임프레임별 설정 (coin + "_15M" 키) |
| 34 | TimingAnalysisService: analyzeOptimalTiming15m() 구현 |
| 35 | TradeResultChecker: 15M 타임프레임 캔들 종료 시점 판정 |
| 36 | 대시보드 탭 UI (1H 초록 / 15M 보라, 콘텐츠 분리) |

### Phase 7: 크리티컬 버그 수정 (2026-02-13)
| # | 작업 | 심각도 |
|---|------|--------|
| 37 | **15M 시초가 버그**: 15M 트레이드가 1H 시초가 사용 → 15M 전용 시초가 수정 | 🔴 CRITICAL |
| 38 | **WIN/LOSE 판정 버그**: closePrice vs entryPrice 비교 → closePrice vs **openPrice** 비교로 수정 | 🔴 CRITICAL |
| 39 | Trade 엔티티에 openPrice 컬럼 추가 + 대시보드 시초가 열 표시 |
| 40 | OpenPriceBackfillInitializer: 기존 트레이드 시초가 백필 (타임프레임별) |
| 41 | TradeResultChecker: getCandleOpenAndClose() 메서드로 리팩토링 |
| 42 | 대시보드 시초가 라벨 동적 전환 ("15M 시작가" / "정시 시작가") |
| 43 | dashboard.html 파일 부패 복구 (4중 HTML 중복, 1337줄→정상화) |
| 44 | 레거시 null 타임프레임 호환 (findTop20ByCoinAndTimeframeIncludingLegacy) |
| 45 | 극단 오즈 UI 수정 (formatCents, oddsBarWidth 최소 3%, 폰트 크기 조절) |

### Phase 8: Claude 프롬프트 최적화 (2026-02-13)
| # | 작업 | 카테고리 |
|---|------|----------|
| 46 | BTC 4H/24H 데이터 수집 추가 (fetchBtcPrice) | 데이터 수집 |
| 47 | calcTrend() 코인별 독립 계산 (BTC=BTC데이터, ETH=ETH데이터+BTC상관) | 분석 로직 |
| 48 | 타임프레임별 과거 성적 분리 (findTop50ByCoinAndTimeframeForStats) | 과거 성적 |
| 49 | buildPatternStats() 강화 (UP/DOWN별 승률, 최근 5건 방향 표시) | 프롬프트 |
| 50 | 캔들 경과 시간 계산 + 종료 임박/초반 경고 | 프롬프트 |
| 51 | 롱숏비율 프롬프트 포함 + 과밀집 경고 | 프롬프트 |
| 52 | 코인별 시장 지표 섹션 (BTC→BTC지표+ETH상관, ETH→ETH지표+BTC상관) | 프롬프트 |
| 53 | 판단 규칙 5개로 확장 (경과 시간 규칙 추가) | 프롬프트 |
| 54 | odds.source() → odds.slug() 컴파일 에러 수정 | 버그 수정 |
| 55 | RSI Wilder's Smoothing 정석 구현 | 기술적 지표 |
| 56 | MACD Signal EMA(9) 정석 구현 + 히스토그램 | 기술적 지표 |
| 57 | 15M 전용 RSI/MACD 계산 (15분봉 100개 기반) | 기술적 지표 |
| 58 | MarketIndicators: rsi15m, macd15m, macdLine15m, macdSignal15m 필드 추가 | DTO |
| 59 | 프롬프트 MACD 표현 강화 (라인+시그널+히스토그램, 강세확대/약세확대) | 프롬프트 |
| 60 | 프롬프트 기술적 지표 타임프레임 분기 (is15m → 15M RSI/MACD 사용) | 프롬프트 |

### Phase 9: 대시보드 15M 차트 (2026-02-13)
| # | 작업 |
|---|------|
| 61 | 15M 탭에 TradingView 15분봉 차트 추가 (BTC/ETH 전환, RSI+MACD 스터디) |
| 62 | 15M 트리거 표시 "+1분" → "1분" 수정 |

---

## 🔴 알려진 이슈 / TODO

| 우선순위 | 항목 | 상태 | 비고 |
|----------|------|------|------|
| 🟡 MID | volume1h 필드 미수집 | 미해결 | MarketIndicators에 필드 있으나 collect()에서 미수집 |
| 🟡 MID | 공포탐욕지수 단기 예측 노이즈 | 미해결 | Daily 데이터를 1H/15M 예측에 사용 — 노이즈 가능성 |
| 🟢 LOW | PolymarketClient mock | 미해결 | 실제 API 아닌 mock 데이터 |
| 🟢 LOW | PolymarketOrderService 실배팅 | 미해결 | LIVE 모드용 실제 주문 실행 |
| 🟢 LOW | AutonomousTrader.java 정리 | 미해결 | 비활성화된 빈 파일 |

---

## 📈 성과 기록

### 첫 번째 오버나이트 테스트 (2026-02-12, 버그 수정 전)
```
포트폴리오: $50.00 → $52.01 (+4.0%)
BTC: 6 bets / 2 WIN / 40% 승률 / -$5.39 PNL
ETH: 7 bets / 5 WIN / 83.3% 승률 / +$14.59 PNL
```

### 두 번째 성과 리뷰 (2026-02-12, 버그 수정 전)
```
포트폴리오: $50.00 → $70.18 (+40.4%)
총 15 bets, 10W 5L (66.7%)
BTC: 57.1% 승률
ETH: 75.0% 승률
```

> ⚠️ 위 성과는 WIN/LOSE 판정 버그(entryPrice vs openPrice) 수정 **전** 수치임.
> Phase 7에서 크리티컬 버그 수정 후 초기화하여 재측정 필요.

---

## 📝 중요 메모

1. **Polymarket 판정**: Close vs **Open** 비교 (Close vs Entry 아님!)
2. **1H 슬러그**: ET(미국 동부시간) 기준 → `PolymarketOddsService`에서 ET 변환
3. **15M 슬러그**: Unix timestamp (초 단위) 기반
4. **가격 소스**: Polymarket은 **Chainlink** 사용 (바이낸스 아님). 미세 차이 존재 가능
5. **레거시 호환**: 15M 기능 추가 전 트레이드는 `timeframe=null` → 1H로 취급
6. **HOLD**: DB에 저장 안 함. TradeResultChecker도 스킵
7. **극단 오즈**: CLOB API 오즈 [15%, 85%] 범위로 클램핑
8. **Claude API**: Sonnet 4.5 사용. 비용 ~$18/월 추정
9. **RSI/MACD**: TradingView와 동일한 계산 방식 (Wilder's Smoothing, EMA 시계열)
10. **트리거**: 서버 시작시 72H 분석 자동 실행. 매시 정각에도 재분석 가능
