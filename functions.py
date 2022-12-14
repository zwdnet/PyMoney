# -*- coding:utf-8 -*-

#功能程序:实现程序的具体功能
import os
from tools import *
import math
import pandas as pd
from scipy import stats


#插入新的收入支出项目
def InsertData():
    #print("insertdata")
    os.system("clear")
    os.system("clear")
    time = input("请输入项目时间:")
    if time.isdecimal() == False:
        ErrorInform("请输入八位数字格式的日期")
        return
    if JudgeDate(int(time)) == False:
        ErrorInform("请输入合法的日期19800101-21000101")
        return
    name = input("请输入项目名称:")
    amount = input("请输入项目金额:")
    if IsNumber(amount) == False:
        ErrorInform("请输入数字金额")
        return
    amount = Yuan2Fen(float(amount))
    maxnum = ShowType()
    typeID = input("请输入项目类型ID(若新增项目，输0):")
    if typeID.isdigit() == False or int(typeID) > maxnum:
        ErrorInform("请输入正确的项目类型ID,插入新的ID请输0。")
        return
    if int(typeID) == 0:
        #增加项目的类型
        typename = input("请输入要增加的项目名称:")
    else:
        typename = GetTypeNamebyID(typeID)
    typeid = GetTypeIDbyName(typename)
    #print(time, name, amount, typeid, typename)
    if typeid == None:
        SetType(typename)
        typeid = GetTypeIDbyName(typename)
    SetValue(time, name, amount, typeid)
    input("数据插入完成，按任意键继续。")
    
    
#按项目时间检索数据库
def SearchByTime():
    os.system("clear")
    Date = InputDateRange()
    if Date == (0, 0):
        return
    sql = "SELECT * FROM Income where Time >= "
    sql += str(Date[0])
    sql += " and Time <= "
    sql += str(Date[1])
    OutputResult(sql)
    
    
#按项目类型检索数据库
def SearchByType():
    os.system("clear")
    maxnum = ShowType()
    option = input("请输入要查询的项目类型ID:")
    if option.isdigit() == False or int(option) > maxnum or int(option) <= 0:
        ErrorInform("请输入正确的项目类型ID,插入新的ID请输0")
        return
    Date = InputDateRange()
    if Date == (0, 0):
        return
    sql = "SELECT * FROM Income where TypeID = "
    sql += option
    sql += " and Time >= "
    sql += str(Date[0])
    sql += " and Time <= "
    sql += str(Date[1])
    OutputResult(sql)
    
    
#删除项目
def DelData():
    os.system("clear")
    ID = input("即将删除收入支出项目，请输入项目ID，如不知道，请通过软件其它功能查询:")
    if ID.isdigit() == False or int(ID) <= 0 :
        ErrorInform("请输入正确的项目类型ID。")
        return
    sql = "select ID from Income where ID ="
    sql += str(ID)
    db = DataBase("money.db")
    db.Execute(sql)
    res = db.GetResult()
    if res == []:
        input("数据库内无此ID，按任意键返回……")
    else:
        sql = "DELETE FROM INCOME WHERE ID = "
        sql += str(ID)
        db.Execute(sql)
        res = db.GetResult()
        input("删除数据成功，按任意键返回……")
    
    
#输出现金流量表
def Status():
    os.system("clear")
    Date = InputDateRange()
    if Date == (0, 0):
        return
    # 计算相关数据
    TotalIncome = GetSumIncome(Date)
    TotalExpense = GetSumExpense(Date)
    Total = GetSum(Date)
    #print(TotalIncome, TotalExpense, Total)
    space = "             "
    line = "------------------"
    print("从%s到%s的现金流量表为" % (Date[0], Date[1]))
    print(line)
    print("总收入=%.2f元，其中" % Fen2Yuan(TotalIncome))
    #查询分类的收入支出总额
    sql = "SELECT TypeID from IncomeType"
    db = DataBase("money.db")
    db.Execute(sql)
    result = db.GetResult()
    for typeID in result:
        typeName = GetTypeNamebyID(typeID[0])
        typeSum = GetSumByTypeID(Date, typeID[0])
        if typeName == None or  typeSum <= 0:
            pass
        else:
            print(space)
            print("%s = %.2f" % (typeName, Fen2Yuan(typeSum)))
    print(line)
    print("总支出=%.2f元，其中" % math.fabs(Fen2Yuan(TotalExpense)))
    for typeID in result:
        typeName = GetTypeNamebyID(typeID[0])
        typeSum = GetSumByTypeID(Date, typeID[0])
        if typeName == None or  typeSum >= 0:
            pass
        else:
            print(space)
            print("%s = %.2f" % (typeName, math.fabs(Fen2Yuan(typeSum))))
    print(line)
    print("净收入为:%.2f" % Fen2Yuan(Total))
    print(line)
    print("税负情况")
    taxTotal = Tax(Date)
    print("纳税总额:%.2f元，税负率:%.2f%%" % (math.fabs(Fen2Yuan(taxTotal)), math.fabs(taxTotal/TotalIncome*100.0)))
    
    input("查询完毕，按任意键继续……")
    
    
# 计算个人税负
def Tax(Date):
    # 计算增值税
    valueAdd6 = "饮食,日常支出,职业发展,通讯,医疗,人际开支,社会活动,婚恋成本,旅行,金融成本,保险,物业费,彩票,学车支出,购房支出,文化消费,学习支出,法律咨询费,用车成本_停车费,用车成本_过路费,用车成本_洗车,用车成本_维修保养,用车成本_代驾,家电维修,用车成本_保险,物业支出,用车成本_咨询费用"
    valueAdd9 = "交通,水电,房租,书刊消费"
    valueAdd13 = "日常用品,大额消费,亲情消费,自行车爱好,衣物,家电消费,软件消费,用车成本_油耗,用车成本_其它费用,用车成本_买配件,电子消费"
    amount6 = getAddAmount(Date, valueAdd6)
    amount9 = getAddAmount(Date, valueAdd9)
    amount13 = getAddAmount(Date, valueAdd13) 
    # 计算增值税
    addValueTax = amount6*0.06 + amount9*0.09 + amount13*0.13
    
    # 计算燃油税，假设油价为9元/升
    oilPrice = 900
    typeid = GetTypeIDbyName("用车成本_油耗")
    oilAmount = -1*GetItemTotal(Date[0], Date[1], typeid)
    oilVolume = oilAmount/oilPrice
    oilTax = oilVolume*152
    # print(oilAmount, oilVolume, oilTax)
    
    # 计算城建税和教育附加
    city_tax = (addValueTax + oilTax)*0.07
    edu_tax = (addValueTax + oilTax)*0.05
    
    # 计算企业所得税
    income_tax = (amount6 + amount9 + amount13)*0.25
    
    # 其它税收，直接按纳税额加起来
    typeid = GetTypeIDbyName("纳税")
    other_tax = -1*GetItemTotal(Date[0], Date[1], typeid)
    typeid = GetTypeIDbyName("用车成本_税收")
    other_tax += -1*GetItemTotal(Date[0], Date[1], typeid)
    
    
    # 计算总税收
    allTax = addValueTax + oilTax + city_tax + edu_tax + income_tax + other_tax
    # print("测试", addValueTax, oilTax, city_tax, edu_tax, other_tax)
    # print("纳税总额:", allTax)
    return allTax
    
    
    
# 获取不同税率项目的增值税应税总额
def getAddAmount(Date, valueItems):
    valueItems = valueItems.split(",")
    amount = 0.0
    for item in valueItems:
        typeid = GetTypeIDbyName(item)
        amount += GetItemTotal(Date[0], Date[1], typeid)
    return -1*amount
    
    
# 取得指定日期内的特定项目的总额
def GetItemTotal(date_start, date_end, typeid):
    sql = "SELECT * FROM Income where TypeID = "
    sql += str(typeid)
    sql += " and Time >= "
    sql += str(date_start)
    sql += " and Time <= "
    sql += str(date_end)
    # OutputResult(sql)
    db = DataBase("money.db")
    db.Execute(sql)
    result = db.GetResult()
    totalAmount = 0.0
    for item in result:
        # typeName = GetTypeNamebyID(item[4])
        # amount = Fen2Yuan(item[3])
        totalAmount += float(item[3])
    return totalAmount
    

# 按年输出指定日期范围的个人CPI及收入变动比率
def CPI():
    space = "             "
    line = "------------------"
    
    beginYear = input("请输入起始年份:")
    endYear = input("请输入结束年份:")
    if beginYear.isdecimal() == False or endYear.isdecimal() == False or len(beginYear) != 4 or len(endYear) != 4:
        ErrorInform("请输入四位数字格式的年份")
        return
    if int(beginYear) >= int(endYear):
        ErrorInform("起始年份需小于结束年份")
        return
    
    begin = int(beginYear)
    end = int(endYear)
    typeid = GetTypeIDbyName("给李娅钱")
    result = pd.DataFrame()
    cpis = []
    years = []
    incomes = []
    for year in range(begin+1, end+1):
        date1_start = str(year-1)+"0101"
        date1_end = str(year-1)+"1231"
        date2_start = str(year)+"0101"
        date2_end = str(year)+"1231"
        TotalIncome1 = GetSumIncome((date1_start, date1_end))
        TotalExpense1 = GetSumExpense((date1_start, date1_end))
        TotalIncome2 = GetSumIncome((date2_start, date2_end))
        TotalExpense2 = GetSumExpense((date2_start, date2_end))
        RemoveAmount1 = GetItemTotal(date1_start, date1_end, typeid)
        RemoveAmount2 = GetItemTotal(date2_start, date2_end, typeid)
        TotalExpense1 -= RemoveAmount1
        TotalExpense2 -= RemoveAmount2
        # 计算数据
        cpis.append(abs(TotalExpense2)/abs(TotalExpense1) - 1.0)
        incomes.append(TotalIncome2/TotalIncome1 - 1.0)
        years.append(year)
    
    result["年度"] = years
    result["通胀率"] = cpis
    result["收入增长率"] = incomes
    result.set_index(keys = "年度", inplace = True)
    print("计算CPI结果")
    print(result)
    min_cpi = result.通胀率.min() - 0.01
    min_income = result.收入增长率.min() - 0.01
    print("年均通胀率:", stats.gmean(result.通胀率.values - min_cpi) + min_cpi)
    print("年均收入增长率:", stats.gmean(result.收入增长率.values - min_income) + min_income)
    print(line)
    input("查询完毕，按任意键继续……")
