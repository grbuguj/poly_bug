#!/usr/bin/env python3
"""Trade data analysis from H2 database"""
import subprocess, json, os, glob, sys

# Find H2 jar
h2_jars = glob.glob(os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/*/*/h2-*.jar"))
if not h2_jars:
    print("H2 jar not found in gradle cache")
    exit(1)

h2_jar = h2_jars[0]
db_url = "jdbc:h2:/Users/jaeung/IdeaProjects/poly_bug/poly-trading-bot"
output_file = "/Users/jaeung/IdeaProjects/poly_bug/trade_analysis.txt"

results = []

queries = {
    "summary": """
        SELECT timeframe, result, COUNT(*) as cnt,
               ROUND(SUM(bet_amount),2) as total_bet,
               ROUND(SUM(profit_loss),2) as total_pnl
        FROM trades
        WHERE action != 'HOLD'
        GROUP BY timeframe, result
        ORDER BY timeframe, result
    """,
    "coin_tf_stats": """
        SELECT coin, timeframe,
               COUNT(*) as total,
               SUM(CASE WHEN result='WIN' THEN 1 ELSE 0 END) as wins,
               SUM(CASE WHEN result='LOSE' THEN 1 ELSE 0 END) as losses,
               SUM(CASE WHEN result='PENDING' THEN 1 ELSE 0 END) as pending,
               ROUND(SUM(profit_loss), 2) as pnl,
               ROUND(AVG(bet_amount), 2) as avg_bet,
               ROUND(AVG(buy_odds), 3) as avg_odds
        FROM trades
        WHERE action != 'HOLD'
        GROUP BY coin, timeframe
        ORDER BY coin, timeframe
    """,
    "detail_15m_all": """
        SELECT id, coin, action, ROUND(bet_amount,2) as bet, ROUND(buy_odds,3) as odds,
               ROUND(open_price,2) as open_p, ROUND(entry_price,2) as entry_p, ROUND(exit_price,2) as exit_p,
               result, ROUND(profit_loss,2) as pnl, confidence as conf,
               FORMATDATETIME(created_at, 'MM-dd HH:mm') as created
        FROM trades
        WHERE (timeframe = '15M') AND action != 'HOLD'
        ORDER BY created_at DESC
    """,
    "detail_5m_recent": """
        SELECT id, coin, action, ROUND(bet_amount,2) as bet, ROUND(buy_odds,3) as odds,
               ROUND(open_price,2) as open_p, ROUND(entry_price,2) as entry_p, ROUND(exit_price,2) as exit_p,
               result, ROUND(profit_loss,2) as pnl, confidence as conf,
               FORMATDATETIME(created_at, 'MM-dd HH:mm') as created
        FROM trades
        WHERE (timeframe = '5M') AND action != 'HOLD'
        ORDER BY created_at DESC
        LIMIT 30
    """,
    "detail_1h_recent": """
        SELECT id, coin, action, ROUND(bet_amount,2) as bet, ROUND(buy_odds,3) as odds,
               ROUND(open_price,2) as open_p, ROUND(entry_price,2) as entry_p, ROUND(exit_price,2) as exit_p,
               result, ROUND(profit_loss,2) as pnl, confidence as conf,
               FORMATDATETIME(created_at, 'MM-dd HH:mm') as created
        FROM trades
        WHERE (timeframe = '1H' OR timeframe IS NULL) AND action != 'HOLD'
        ORDER BY created_at DESC
        LIMIT 30
    """,
    "win_lose_patterns_by_tf": """
        SELECT timeframe, result,
               COUNT(*) as cnt,
               ROUND(AVG(buy_odds), 3) as avg_odds,
               ROUND(AVG(ABS((entry_price - open_price) / NULLIF(open_price,0) * 100)), 4) as avg_move_pct,
               ROUND(AVG(confidence), 1) as avg_conf,
               ROUND(AVG(bet_amount), 2) as avg_bet,
               ROUND(SUM(profit_loss), 2) as total_pnl
        FROM trades
        WHERE action != 'HOLD' AND result IN ('WIN', 'LOSE')
        GROUP BY timeframe, result
        ORDER BY timeframe, result
    """,
    "open_entry_diff_15m": """
        SELECT id, coin, action, result,
               ROUND(open_price,2) as open_p,
               ROUND(entry_price,2) as entry_p,
               ROUND(exit_price,2) as exit_p,
               ROUND((entry_price - open_price) / NULLIF(open_price,0) * 100, 4) as entry_vs_open_pct,
               ROUND((exit_price - open_price) / NULLIF(open_price,0) * 100, 4) as close_vs_open_pct,
               FORMATDATETIME(created_at, 'MM-dd HH:mm') as created,
               ROUND(profit_loss,2) as pnl
        FROM trades
        WHERE action != 'HOLD' AND open_price > 0 AND timeframe = '15M'
        ORDER BY created_at DESC
    """,
    "action_distribution": """
        SELECT timeframe, action, result, COUNT(*) as cnt
        FROM trades
        WHERE action != 'HOLD'
        GROUP BY timeframe, action, result
        ORDER BY timeframe, action, result
    """
}

with open(output_file, 'w') as f:
    for name, sql in queries.items():
        f.write(f"\n{'='*80}\n")
        f.write(f"  {name}\n")
        f.write(f"{'='*80}\n")
        try:
            result = subprocess.run(
                ["java", "-cp", h2_jar, "org.h2.tools.Shell",
                 "-url", db_url, "-user", "sa", "-password", "",
                 "-sql", sql.strip()],
                capture_output=True, text=True, timeout=15
            )
            if result.stdout.strip():
                f.write(result.stdout.strip() + "\n")
            if result.stderr.strip():
                f.write("STDERR: " + result.stderr.strip() + "\n")
        except Exception as e:
            f.write(f"ERROR: {e}\n")

print(f"Analysis written to {output_file}")
