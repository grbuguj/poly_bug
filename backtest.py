#!/usr/bin/env python3
"""
폴리마켓 오즈갭 전략 백테스팅
핵심: "캔들 진행 중 가격이 X% 변동 → 종가도 같은 방향일 확률은?"
"""
import requests
import time
import json
from collections import defaultdict

BINANCE_URL = "https://api.binance.com/api/v3/klines"

def fetch_klines(symbol, interval, days=30):
    all_data = []
    end_time = int(time.time() * 1000)
    start_time = end_time - (days * 24 * 60 * 60 * 1000)
    current = start_time
    while current < end_time:
        params = {'symbol': symbol, 'interval': interval, 'startTime': current, 'limit': 1000}
        try:
            resp = requests.get(BINANCE_URL, params=params, timeout=10)
            data = resp.json()
            if not data: break
            all_data.extend(data)
            current = data[-1][0] + 1
            time.sleep(0.05)
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)
    return all_data

def analyze(symbol, coin, candle_min, tf_label, df_1m):
    """1분봉으로 N분 캔들 내 스냅샷 분석"""
    records = []
    
    # 1분봉을 N분 윈도우로 그룹핑
    windows = defaultdict(list)
    for row in df_1m:
        ts = row[0]  # open_time ms
        minute_of_day = (ts // 60000) % 1440
        window_id = (ts // (candle_min * 60000))  # 고유 윈도우
        windows[window_id].append(row)
    
    total_candles = 0
    for wid, bars in windows.items():
        if len(bars) < candle_min:
            continue
        total_candles += 1
        
        candle_open = float(bars[0][1])  # open of first bar
        candle_close = float(bars[-1][4])  # close of last bar
        
        if candle_open <= 0 or candle_close == candle_open:
            continue
        
        final_up = candle_close > candle_open
        
        for i, bar in enumerate(bars):
            elapsed_min = i + 1
            snapshot_price = float(bar[4])  # close of this minute
            change_pct = ((snapshot_price - candle_open) / candle_open) * 100
            elapsed_pct = elapsed_min / candle_min
            
            if abs(change_pct) < 0.001:
                continue
            
            snapshot_up = change_pct > 0
            same_dir = (snapshot_up == final_up)
            
            records.append((elapsed_pct, abs(change_pct), same_dir))
    
    return records, total_candles

def main():
    # 현재 모델 확률 테이블 (OddsGapScanner estimateProbFromPriceMove)
    model_table = [
        (0.05, 0.51), (0.08, 0.52), (0.10, 0.54), (0.15, 0.57),
        (0.25, 0.61), (0.35, 0.66), (0.50, 0.73), (0.70, 0.80), (1.00, 0.85)
    ]
    
    def get_model_prob(pct):
        prob = 0.51
        for threshold, p in model_table:
            if pct >= threshold: prob = p
        return prob
    
    coins = [('BTCUSDT', 'BTC'), ('ETHUSDT', 'ETH'), ('SOLUSDT', 'SOL'), ('XRPUSDT', 'XRP')]
    timeframes = [(5, '5M'), (15, '15M'), (60, '1H')]
    
    change_buckets = [(0.03, 0.08), (0.08, 0.15), (0.15, 0.25), (0.25, 0.35), 
                      (0.35, 0.50), (0.50, 0.70), (0.70, 1.00), (1.00, 2.00), (2.00, 5.00)]
    
    print("=" * 75)
    print("🚀 폴리마켓 오즈갭 전략 백테스팅 (최근 30일)")
    print("=" * 75)
    
    all_summary = []
    
    for symbol, coin in coins:
        print(f"\n📥 {coin} 1분봉 로딩 중...")
        df_1m = fetch_klines(symbol, '1m', days=30)
        print(f"   ✅ {len(df_1m):,}개 1분봉")
        
        for candle_min, tf_label in timeframes:
            label = f"{coin} {tf_label}"
            records, n_candles = analyze(symbol, coin, candle_min, tf_label, df_1m)
            
            if not records:
                print(f"   ⚠️ {label}: 데이터 없음")
                continue
            
            print(f"\n{'─'*75}")
            print(f"📊 {label} | 캔들 {n_candles:,}개 | 스냅샷 {len(records):,}개")
            print(f"{'─'*75}")
            
            # ── 1) 변동폭별 확률 테이블 ──
            print(f"{'변동폭':>12} | {'표본':>7} | {'실제승률':>8} | {'모델':>6} | {'차이':>8} | 판정")
            print("-" * 65)
            
            for lo, hi in change_buckets:
                subset = [(e, c, s) for e, c, s in records if lo <= c < hi]
                if len(subset) < 30:
                    continue
                win_rate = sum(1 for _, _, s in subset if s) / len(subset)
                mid = (lo + hi) / 2
                model_p = get_model_prob(mid)
                diff = win_rate - model_p
                
                if diff < -0.05:
                    verdict = "🔴 모델 과대추정"
                elif diff < -0.02:
                    verdict = "🟡 약간 과대"
                elif diff > 0.05:
                    verdict = "🟢 기회 과소평가"
                elif diff > 0.02:
                    verdict = "🟢 약간 과소"
                else:
                    verdict = "✅ 적정"
                
                print(f"{lo:.2f}-{hi:.2f}% | {len(subset):>7,} | {win_rate:>7.1%} | {model_p:>5.0%} | {diff:>+7.1%} | {verdict}")
                
                all_summary.append({
                    'label': label, 'bucket': f"{lo:.2f}-{hi:.2f}",
                    'count': len(subset), 'win_rate': win_rate,
                    'model': model_p, 'diff': diff
                })
            
            # ── 2) 경과시간별 확률 (변동 0.1%+) ──
            big_moves = [(e, c, s) for e, c, s in records if c >= 0.10]
            if big_moves:
                print(f"\n  ⏱️ 경과시간별 승률 (변동 0.1%+, n={len(big_moves):,}):")
                time_bins = [(0.0, 0.2, "초반 0-20%"), (0.2, 0.4, "중반초 20-40%"), 
                             (0.4, 0.6, "중반 40-60%"), (0.6, 0.8, "중반후 60-80%"), (0.8, 1.0, "후반 80-100%")]
                for tlo, thi, tlabel in time_bins:
                    subset = [(e, c, s) for e, c, s in big_moves if tlo <= e < thi]
                    if len(subset) < 30: continue
                    wr = sum(1 for _, _, s in subset if s) / len(subset)
                    print(f"    {tlabel}: {wr:.1%} (n={len(subset):,})")
            
            # ── 3) 전략 시뮬레이션 ──
            # 캔들 15-85% 구간 + 변동 0.1%+ (실제 배팅 조건과 유사)
            mid_big = [(e, c, s) for e, c, s in records if 0.15 <= e <= 0.85 and c >= 0.10]
            if mid_big:
                wr = sum(1 for _, _, s in mid_big if s) / len(mid_big)
                print(f"\n  💰 전략 시뮬 (캔들 15-85%, 변동 0.1%+): 승률 {wr:.1%} (n={len(mid_big):,})")
                
                for odds_str, odds_val in [("45%", 0.45), ("50%", 0.50), ("55%", 0.55), ("60%", 0.60)]:
                    payout = (1.0 / odds_val) - 1.0
                    ev = wr * payout - (1 - wr)
                    total_1000 = ev * 1000
                    emoji = "✅" if ev > 0 else "❌"
                    print(f"    오즈 {odds_str}: EV/bet = ${ev:+.4f} | 1000회 = ${total_1000:+.1f} {emoji}")
    
    # ═══════════════════════════════════════
    # 최종 결론
    # ═══════════════════════════════════════
    print(f"\n\n{'='*75}")
    print("🏆 최종 결론")
    print(f"{'='*75}")
    
    # 모델 과대추정 비율
    over = [s for s in all_summary if s['diff'] < -0.03 and s['count'] >= 100]
    under = [s for s in all_summary if s['diff'] > 0.03 and s['count'] >= 100]
    ok = [s for s in all_summary if abs(s['diff']) <= 0.03 and s['count'] >= 100]
    total_valid = len([s for s in all_summary if s['count'] >= 100])
    
    print(f"\n  표본 100+ 버킷 {total_valid}개 중:")
    print(f"    🔴 모델 과대추정 (실제 < 모델-3%): {len(over)}개")
    print(f"    ✅ 적정 (±3% 이내):                {len(ok)}개")
    print(f"    🟢 기회 과소평가 (실제 > 모델+3%): {len(under)}개")
    
    # 큰 변동에서의 평균 승률
    big_bucket_rates = [s for s in all_summary if s['count'] >= 100 and float(s['bucket'].split('-')[0]) >= 0.25]
    if big_bucket_rates:
        avg_wr = sum(s['win_rate'] for s in big_bucket_rates) / len(big_bucket_rates)
        avg_model = sum(s['model'] for s in big_bucket_rates) / len(big_bucket_rates)
        print(f"\n  큰 변동(0.25%+) 평균:")
        print(f"    실제 승률: {avg_wr:.1%}")
        print(f"    모델 추정: {avg_model:.1%}")
        print(f"    차이: {avg_wr - avg_model:+.1%}")
    
    if avg_wr > 0.55:
        print(f"\n  → 실제 승률 55%+ → 적절한 오즈만 받으면 수익 가능성 있음")
    elif avg_wr > 0.52:
        print(f"\n  → 실제 승률 52-55% → 매우 좋은 오즈(40% 이하)가 필요")
    else:
        print(f"\n  → 실제 승률 52% 미만 → 현재 전략으로는 수익 어려움")

if __name__ == '__main__':
    main()
