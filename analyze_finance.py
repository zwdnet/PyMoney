#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
财务数据分析程序
分析SQLite数据库中的收入和支出记录，生成可视化图表
"""

import sqlite3
import os
import matplotlib
matplotlib.use('Agg')  # 非交互式后端，适合服务器运行
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from datetime import datetime
import numpy as np
import run

# 设置中文字体
plt.rcParams['font.sans-serif'] = ['SimHei', 'WenQuanYi Micro Hei', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# 配置
DB_PATH = './money.db'
OUTPUT_DIR = './output'

def ensure_output_dir():
    """确保输出目录存在"""
    os.makedirs(OUTPUT_DIR, exist_ok=True)

def fetch_data():
    """从数据库获取数据"""
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    
    # 获取所有收支记录
    incomes = conn.execute('''
        SELECT i.ID, i.Time, i.Name, i.Amount, t.TypeName
        FROM Income i
        JOIN IncomeType t ON i.TypeID = t.TypeID
        ORDER BY i.Time
    ''').fetchall()
    
    # 获取所有类型
    types = conn.execute('SELECT * FROM IncomeType ORDER BY TypeID').fetchall()
    
    conn.close()
    return incomes, types

def parse_time(time_str):
    """解析时间字符串 YYYYMMDD -> datetime"""
    return datetime.strptime(str(time_str), '%Y%m%d')

def fen_to_yuan(fen):
    """分转元"""
    return fen / 100.0

@run.change_dir
def save_chart(fig, filename):
    """保存图表"""
    filepath = os.path.join(OUTPUT_DIR, filename)
    fig.savefig(filepath, dpi=150, bbox_inches='tight')
    plt.close(fig)
    print(f"  已保存: {filepath}")

def analyze_overview(incomes):
    """总体概况分析"""
    print("\n" + "="*60)
    print("一、总体概况")
    print("="*60)
    
    total_income = sum(r['Amount'] for r in incomes if r['Amount'] > 0)
    total_expense = sum(r['Amount'] for r in incomes if r['Amount'] < 0)
    balance = total_income + total_expense
    
    income_count = sum(1 for r in incomes if r['Amount'] > 0)
    expense_count = sum(1 for r in incomes if r['Amount'] < 0)
    
    start_date = parse_time(incomes[0]['Time'])
    end_date = parse_time(incomes[-1]['Time'])
    days_span = (end_date - start_date).days
    
    print(f"记录总数: {len(incomes)} 条")
    print(f"  收入笔数: {income_count}")
    print(f"  支出笔数: {expense_count}")
    print(f"时间跨度: {start_date.strftime('%Y-%m-%d')} 至 {end_date.strftime('%Y-%m-%d')} ({days_span}天)")
    print(f"总收入: {fen_to_yuan(total_income):,.2f} 元")
    print(f"总支出: {fen_to_yuan(abs(total_expense)):,.2f} 元")
    print(f"净结余: {fen_to_yuan(balance):,.2f} 元")
    
    if days_span > 0:
        daily_avg_income = total_income / days_span
        daily_avg_expense = abs(total_expense) / days_span
        print(f"日均收入: {fen_to_yuan(daily_avg_income):,.2f} 元")
        print(f"日均支出: {fen_to_yuan(daily_avg_expense):,.2f} 元")
    
    return total_income, total_expense, balance

def analyze_by_type(incomes, types):
    """按类型分析"""
    print("\n" + "="*60)
    print("二、按类型分析")
    print("="*60)
    
    type_stats = {}
    for t in types:
        type_stats[t['TypeName']] = {'income': 0, 'expense': 0, 'count': 0}
    
    for record in incomes:
        tname = record['TypeName']
        if tname not in type_stats:
            type_stats[tname] = {'income': 0, 'expense': 0, 'count': 0}
        
        if record['Amount'] > 0:
            type_stats[tname]['income'] += record['Amount']
        else:
            type_stats[tname]['expense'] += abs(record['Amount'])
        type_stats[tname]['count'] += 1
    
    # 收入类型统计
    income_types = {k: v for k, v in type_stats.items() if v['income'] > 0}
    expense_types = {k: v for k, v in type_stats.items() if v['expense'] > 0}
    
    print("\n收入分类:")
    for name, stats in sorted(income_types.items(), key=lambda x: x[1]['income'], reverse=True):
        print(f"  {name}: {fen_to_yuan(stats['income']):,.2f} 元 ({stats['count']}笔)")
    
    print("\n支出分类:")
    for name, stats in sorted(expense_types.items(), key=lambda x: x[1]['expense'], reverse=True):
        print(f"  {name}: {fen_to_yuan(stats['expense']):,.2f} 元 ({stats['count']}笔)")
    
    # 绘制支出类型饼图
    if expense_types:
        fig, axes = plt.subplots(1, 2, figsize=(16, 7))
        
        # 支出饼图
        exp_names = list(expense_types.keys())
        exp_values = [expense_types[n]['expense'] for n in exp_names]
        colors = plt.cm.Set3(np.linspace(0, 1, len(exp_names)))
        
        axes[0].pie(exp_values, labels=exp_names, autopct='%1.1f%%', colors=colors, startangle=90)
        axes[0].set_title('支出类型分布', fontsize=14, fontweight='bold')
        
        # 支出柱状图
        sorted_exp = sorted(expense_types.items(), key=lambda x: x[1]['expense'], reverse=True)
        names = [x[0] for x in sorted_exp]
        values = [fen_to_yuan(x[1]['expense']) for x in sorted_exp]
        
        axes[1].barh(names[::-1], values[::-1], color=plt.cm.Set2(np.linspace(0, 1, len(names))))
        axes[1].set_xlabel('金额 (元)')
        axes[1].set_title('支出类型排行', fontsize=14, fontweight='bold')
        for i, v in enumerate(values[::-1]):
            axes[1].text(v, i, f' {v:,.0f}', va='center')
        
        fig.suptitle('支出类型分析', fontsize=16, fontweight='bold')
        plt.tight_layout()
        save_chart(fig, 'expense_by_type.png')
    
    # 绘制收入类型饼图
    if income_types:
        fig, ax = plt.subplots(figsize=(10, 8))
        inc_names = list(income_types.keys())
        inc_values = [income_types[n]['income'] for n in inc_names]
        colors = plt.cm.Pastel1(np.linspace(0, 1, len(inc_names)))
        
        ax.pie(inc_values, labels=inc_names, autopct='%1.1f%%', colors=colors, startangle=90)
        ax.set_title('收入类型分布', fontsize=14, fontweight='bold')
        plt.tight_layout()
        save_chart(fig, 'income_by_type.png')
    
    return type_stats

def analyze_yearly_trend(incomes):
    """年度趋势分析"""
    print("\n" + "="*60)
    print("三、年度趋势分析")
    print("="*60)
    
    yearly_stats = {}
    for record in incomes:
        year = parse_time(record['Time']).year
        if year not in yearly_stats:
            yearly_stats[year] = {'income': 0, 'expense': 0}
        
        if record['Amount'] > 0:
            yearly_stats[year]['income'] += record['Amount']
        else:
            yearly_stats[year]['expense'] += abs(record['Amount'])
    
    years = sorted(yearly_stats.keys())
    inc_values = [fen_to_yuan(yearly_stats[y]['income']) for y in years]
    exp_values = [fen_to_yuan(yearly_stats[y]['expense']) for y in years]
    balances = [i - e for i, e in zip(inc_values, exp_values)]
    
    print(f"\n{'年份':<8} {'收入(元)':>15} {'支出(元)':>15} {'结余(元)':>15}")
    print("-" * 55)
    for i, year in enumerate(years):
        print(f"{year:<8} {inc_values[i]:>15,.2f} {exp_values[i]:>15,.2f} {balances[i]:>15,.2f}")
    
    # 绘制年度趋势图
    fig, axes = plt.subplots(2, 1, figsize=(14, 10))
    
    x = np.arange(len(years))
    width = 0.35
    
    axes[0].bar(x - width/2, inc_values, width, label='收入', color='#2ecc71')
    axes[0].bar(x + width/2, exp_values, width, label='支出', color='#e74c3c')
    axes[0].set_xlabel('年份')
    axes[0].set_ylabel('金额 (元)')
    axes[0].set_title('年度收支对比', fontsize=14, fontweight='bold')
    axes[0].set_xticks(x)
    axes[0].set_xticklabels(years, rotation=45)
    axes[0].legend()
    axes[0].grid(axis='y', alpha=0.3)
    
    balance_colors = ['#2ecc71' if b >= 0 else '#e74c3c' for b in balances]
    axes[1].bar(years, balances, color=balance_colors)
    axes[1].axhline(y=0, color='black', linewidth=0.5)
    axes[1].set_xlabel('年份')
    axes[1].set_ylabel('结余 (元)')
    axes[1].set_title('年度净结余', fontsize=14, fontweight='bold')
    axes[1].grid(axis='y', alpha=0.3)
    
    fig.suptitle('年度财务趋势', fontsize=16, fontweight='bold')
    plt.tight_layout()
    save_chart(fig, 'yearly_trend.png')
    
    return yearly_stats

def analyze_monthly_trend(incomes):
    """月度趋势分析（最近24个月）"""
    print("\n" + "="*60)
    print("四、月度趋势分析（最近24个月）")
    print("="*60)
    
    monthly_stats = {}
    for record in incomes:
        time_str = str(record['Time'])
        month_key = time_str[:6]  # YYYYMM
        if month_key not in monthly_stats:
            monthly_stats[month_key] = {'income': 0, 'expense': 0}
        
        if record['Amount'] > 0:
            monthly_stats[month_key]['income'] += record['Amount']
        else:
            monthly_stats[month_key]['expense'] += abs(record['Amount'])
    
    # 取最近24个月
    sorted_months = sorted(monthly_stats.keys())[-24:]
    months = [datetime.strptime(m, '%Y%m') for m in sorted_months]
    inc_values = [fen_to_yuan(monthly_stats[m]['income']) for m in sorted_months]
    exp_values = [fen_to_yuan(monthly_stats[m]['expense']) for m in sorted_months]
    
    # 绘制月度趋势图
    fig, axes = plt.subplots(2, 1, figsize=(14, 10))
    
    # 收支折线图
    axes[0].plot(months, inc_values, 'g-', marker='o', markersize=4, label='收入', linewidth=2)
    axes[0].plot(months, exp_values, 'r-', marker='s', markersize=4, label='支出', linewidth=2)
    axes[0].fill_between(months, inc_values, alpha=0.1, color='green')
    axes[0].fill_between(months, exp_values, alpha=0.1, color='red')
    axes[0].set_xlabel('月份')
    axes[0].set_ylabel('金额 (元)')
    axes[0].set_title('月度收支趋势', fontsize=14, fontweight='bold')
    axes[0].legend()
    axes[0].grid(alpha=0.3)
    axes[0].xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m'))
    axes[0].tick_params(axis='x', rotation=45)
    
    # 月度结余柱状图
    balances = [i - e for i, e in zip(inc_values, exp_values)]
    colors = ['#2ecc71' if b >= 0 else '#e74c3c' for b in balances]
    axes[1].bar(months, balances, color=colors, width=15)
    axes[1].axhline(y=0, color='black', linewidth=0.5)
    axes[1].set_xlabel('月份')
    axes[1].set_ylabel('结余 (元)')
    axes[1].set_title('月度净结余', fontsize=14, fontweight='bold')
    axes[1].grid(axis='y', alpha=0.3)
    axes[1].xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m'))
    axes[1].tick_params(axis='x', rotation=45)
    
    fig.suptitle('月度财务趋势（最近24个月）', fontsize=16, fontweight='bold')
    plt.tight_layout()
    save_chart(fig, 'monthly_trend.png')

def analyze_spending_categories(incomes):
    """支出类别详细分析 - 堆叠柱状图（按年份）"""
    print("\n" + "="*60)
    print("五、支出类别年度变化")
    print("="*60)
    
    # 按年份和类型统计支出
    yearly_type_expense = {}
    for record in incomes:
        if record['Amount'] < 0:
            year = parse_time(record['Time']).year
            tname = record['TypeName']
            if year not in yearly_type_expense:
                yearly_type_expense[year] = {}
            if tname not in yearly_type_expense[year]:
                yearly_type_expense[year][tname] = 0
            yearly_type_expense[year][tname] += abs(record['Amount'])
    
    # 获取主要支出类型（总支出前8）
    type_total = {}
    for year_data in yearly_type_expense.values():
        for tname, amount in year_data.items():
            type_total[tname] = type_total.get(tname, 0) + amount
    
    top_types = sorted(type_total.keys(), key=lambda x: type_total[x], reverse=True)[:8]
    
    years = sorted(yearly_type_expense.keys())
    colors = plt.cm.Set3(np.linspace(0, 1, len(top_types)))
    
    fig, ax = plt.subplots(figsize=(14, 8))
    
    bottom = np.zeros(len(years))
    for i, tname in enumerate(top_types):
        values = [fen_to_yuan(yearly_type_expense.get(y, {}).get(tname, 0)) for y in years]
        ax.bar(years, values, bottom=bottom, label=tname, color=colors[i])
        bottom += np.array(values)
    
    ax.set_xlabel('年份', fontsize=12)
    ax.set_ylabel('支出金额 (元)', fontsize=12)
    ax.set_title('年度支出类别构成', fontsize=14, fontweight='bold')
    ax.legend(loc='upper left', bbox_to_anchor=(1, 1))
    ax.grid(axis='y', alpha=0.3)
    
    plt.tight_layout()
    save_chart(fig, 'yearly_expense_categories.png')

def analyze_income_expense_ratio(incomes):
    """收支比率分析"""
    print("\n" + "="*60)
    print("六、储蓄率分析")
    print("="*60)
    
    yearly_stats = {}
    for record in incomes:
        year = parse_time(record['Time']).year
        if year not in yearly_stats:
            yearly_stats[year] = {'income': 0, 'expense': 0}
        
        if record['Amount'] > 0:
            yearly_stats[year]['income'] += record['Amount']
        else:
            yearly_stats[year]['expense'] += abs(record['Amount'])
    
    years = sorted(yearly_stats.keys())
    saving_rates = []
    
    for year in years:
        inc = yearly_stats[year]['income']
        exp = yearly_stats[year]['expense']
        if inc > 0:
            rate = (inc - exp) / inc * 100
            saving_rates.append(rate)
    
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    
    # 储蓄率折线图
    axes[0].plot(years, saving_rates, 'b-', marker='o', linewidth=2, markersize=6)
    axes[0].axhline(y=0, color='gray', linewidth=0.5)
    axes[0].set_xlabel('年份')
    axes[0].set_ylabel('储蓄率 (%)')
    axes[0].set_title('年度储蓄率变化', fontsize=14, fontweight='bold')
    axes[0].grid(alpha=0.3)
    
    # 储蓄率分布直方图
    axes[1].hist(saving_rates, bins=20, color='#3498db', edgecolor='white', alpha=0.7)
    axes[1].axvline(x=np.mean(saving_rates), color='red', linestyle='--', 
                    label=f'平均: {np.mean(saving_rates):.1f}%')
    axes[1].set_xlabel('储蓄率 (%)')
    axes[1].set_ylabel('年份数量')
    axes[1].set_title('储蓄率分布', fontsize=14, fontweight='bold')
    axes[1].legend()
    axes[1].grid(axis='y', alpha=0.3)
    
    fig.suptitle('储蓄率分析', fontsize=16, fontweight='bold')
    plt.tight_layout()
    save_chart(fig, 'saving_rate.png')

def analyze_top_expenses(incomes):
    """大额支出分析"""
    print("\n" + "="*60)
    print("七、大额支出分析 (Top 20)")
    print("="*60)
    
    expenses = [r for r in incomes if r['Amount'] < 0]
    expenses.sort(key=lambda x: x['Amount'])
    
    top_20 = expenses[:20]
    print(f"\n{'排名':<6} {'日期':<12} {'名称':<20} {'金额(元)':>12} {'类型':<15}")
    print("-" * 70)
    for i, exp in enumerate(top_20):
        date_str = parse_time(exp['Time']).strftime('%Y-%m-%d')
        print(f"{i+1:<6} {date_str:<12} {exp['Name']:<20} {fen_to_yuan(exp['Amount']):>12,.2f} {exp['TypeName']:<15}")
    
    # 绘制大额支出类型分布
    type_count = {}
    for exp in top_20:
        tname = exp['TypeName']
        type_count[tname] = type_count.get(tname, 0) + 1
    
    fig, ax = plt.subplots(figsize=(10, 6))
    types = list(type_count.keys())
    counts = list(type_count.values())
    ax.barh(types, counts, color=plt.cm.RdYlGn(np.linspace(0.2, 0.8, len(types))))
    ax.set_xlabel('出现次数')
    ax.set_title('Top 20大额支出类型分布', fontsize=14, fontweight='bold')
    for i, v in enumerate(counts):
        ax.text(v, i, f' {v}', va='center')
    plt.tight_layout()
    save_chart(fig, 'top_expenses.png')

def main():
    """主函数"""
    print("="*60)
    print("        个人财务数据分析报告")
    print("="*60)
    
    ensure_output_dir()
    
    print("\n正在加载数据...")
    incomes, types = fetch_data()
    print(f"已加载 {len(incomes)} 条记录，{len(types)} 个类型")
    
    # 执行各项分析
    analyze_overview(incomes)
    analyze_by_type(incomes, types)
    analyze_yearly_trend(incomes)
    analyze_monthly_trend(incomes)
    analyze_spending_categories(incomes)
    analyze_income_expense_ratio(incomes)
    analyze_top_expenses(incomes)
    
    print("\n" + "="*60)
    print("分析完成！所有图表已保存到 ./output/ 目录")
    print("="*60)

if __name__ == '__main__':
    main()
